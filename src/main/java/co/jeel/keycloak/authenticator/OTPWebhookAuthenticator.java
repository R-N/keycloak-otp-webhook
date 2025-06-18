package co.jeel.keycloak.authenticator;

import java.net.http.HttpResponse;

import co.jeel.keycloak.authenticator.utils.HmacUtils;
import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.common.util.SecretGenerator;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.AuthenticatorConfigModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import co.jeel.keycloak.authenticator.models.OTPWebhookAuthenticatorAuthNote;
import co.jeel.keycloak.authenticator.models.OTPWebhookAuthenticatorConfig;
import co.jeel.keycloak.authenticator.models.UserAttributes;
import co.jeel.keycloak.authenticator.webhook.WebhookSPIIntegrationHelper;
import co.jeel.keycloak.authenticator.webhook.WebhookSPIRequest;
import com.fasterxml.jackson.databind.ObjectMapper;

public class OTPWebhookAuthenticator implements Authenticator {

	private static final Logger LOGGER = Logger.getLogger(OTPWebhookAuthenticator.class);
	private static final String OTP_INPUT_FORM = "enter-otp.ftl";
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	@Override
	public void authenticate(AuthenticationFlowContext context) {
		try {
			AuthenticatorConfigModel configModel = context.getAuthenticatorConfig();

			OTPWebhookAuthenticatorConfig config = OBJECT_MAPPER.convertValue(configModel.getConfig(),
					OTPWebhookAuthenticatorConfig.class);

			String otp;
			if (config.isMocked()) {
				otp = "3333";
			} else {
            	otp = SecretGenerator.getInstance().randomString(config.getOtpLength(), config.getAllowedChars());
			}

            long otpExpiryTimestamp = System.currentTimeMillis() + (config.getOtpExpirySeconds() * 1000L);
			String otpExpiry = Long.toString(otpExpiryTimestamp);

			AuthenticationSessionModel authSession = context.getAuthenticationSession();
			authSession.setAuthNote(OTPWebhookAuthenticatorAuthNote.OTP.name(), otp);
			authSession.setAuthNote(OTPWebhookAuthenticatorAuthNote.OTP_EXPIRY.name(), otpExpiry);

			if (!config.isMocked()) {
				UserModel user = context.getUser();
				String userIdentifier = user.getFirstAttribute(config.getUserIdentifyingAttribute());

				WebhookSPIRequest webhookRequest = new WebhookSPIRequest(userIdentifier, otp, otpExpiryTimestamp);
				String requestBody = OBJECT_MAPPER.writeValueAsString(webhookRequest);
				String hmacSignature = HmacUtils.getHmacSignature(context.getRealm().getName(), webhookRequest);
				HttpResponse<String> response = WebhookSPIIntegrationHelper.trigger(config.getWebhook(),
						config.getTimeoutSeconds(), requestBody, hmacSignature,
						config.isEnableLogging());

				if (response.statusCode() >= 400) {
					throw new RuntimeException(response.body());
				}
			}

			context.challenge(context.form().setAttribute("realm", context.getRealm()).createForm(OTP_INPUT_FORM));
		} catch (Exception e) {
			LOGGER.error("Exception in authenticate", e);
			context.failureChallenge(AuthenticationFlowError.INTERNAL_ERROR,
					context.form().setError("webhookError", e.getMessage())
							.createErrorPage(Response.Status.INTERNAL_SERVER_ERROR));
		}
	}

	@Override
	public void action(AuthenticationFlowContext context) {
		String enteredOtp = context.getHttpRequest().getDecodedFormParameters().getFirst("otp");

		AuthenticationSessionModel authSession = context.getAuthenticationSession();
		String otp = authSession.getAuthNote(OTPWebhookAuthenticatorAuthNote.OTP.name());
		String otpExpiry = authSession.getAuthNote(OTPWebhookAuthenticatorAuthNote.OTP_EXPIRY.name());

		if (otp == null || otpExpiry == null) {
			LOGGER.error("Required AuthNotes not found, otp: " + otp + ", otpExpiry: " + otpExpiry);
			context.failureChallenge(AuthenticationFlowError.INTERNAL_ERROR,
					context.form().createErrorPage(Response.Status.INTERNAL_SERVER_ERROR));
			return;
		}

		boolean isValid = otp.equals(enteredOtp);
		if (isValid) {
			if (Long.parseLong(otpExpiry) < System.currentTimeMillis()) {
				// expired
				context.failureChallenge(AuthenticationFlowError.EXPIRED_CODE,
						context.form().setError("otpExpired").createErrorPage(Response.Status.BAD_REQUEST));
			} else {
				// valid
				context.success();
			}
		} else {
			// invalid
			AuthenticationExecutionModel execution = context.getExecution();
			if (execution.isRequired()) {
				context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS,
						context.form().setAttribute("realm", context.getRealm()).setError("otpInvalid")
								.createForm(OTP_INPUT_FORM));
			} else if (execution.isConditional() || execution.isAlternative()) {
				context.attempted();
			}
		}
	}

	@Override
	public boolean requiresUser() {
		return true;
	}

	@Override
	public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
		String userOtpEnabled = user.getFirstAttribute(UserAttributes.ENABLE_OTP.name());
		// By Default OTP is Enabled unless explicitly disabled by setting false for a
		// user
		return userOtpEnabled == null || Boolean.valueOf(userOtpEnabled);
	}

	@Override
	public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
		// Required Action can be set to trigger an action to enter user attribute if
		// missing
		// user.addRequiredAction("custom-require-action");
	}

	@Override
	public void close() {
	}

}
