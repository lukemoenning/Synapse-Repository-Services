package org.sagebionetworks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.client.SynapseAdminClient;
import org.sagebionetworks.client.SynapseClient;
import org.sagebionetworks.client.SynapseClientImpl;
import org.sagebionetworks.client.exceptions.SynapseException;
import org.sagebionetworks.client.exceptions.SynapseForbiddenException;
import org.sagebionetworks.client.exceptions.SynapseTwoFactorAuthRequiredException;
import org.sagebionetworks.client.exceptions.SynapseUnauthorizedException;
import org.sagebionetworks.repo.model.auth.AccessTokenGenerationRequest;
import org.sagebionetworks.repo.model.auth.ChangePasswordWithCurrentPassword;
import org.sagebionetworks.repo.model.auth.ChangePasswordWithTwoFactorAuthToken;
import org.sagebionetworks.repo.model.auth.LoginRequest;
import org.sagebionetworks.repo.model.auth.LoginResponse;
import org.sagebionetworks.repo.model.auth.TotpSecret;
import org.sagebionetworks.repo.model.auth.TotpSecretActivationRequest;
import org.sagebionetworks.repo.model.auth.TwoFactorAuthDisableRequest;
import org.sagebionetworks.repo.model.auth.TwoFactorAuthLoginRequest;
import org.sagebionetworks.repo.model.auth.TwoFactorAuthOtpType;
import org.sagebionetworks.repo.model.auth.TwoFactorAuthRecoveryCodes;
import org.sagebionetworks.repo.model.auth.TwoFactorAuthResetRequest;
import org.sagebionetworks.repo.model.auth.TwoFactorAuthResetToken;
import org.sagebionetworks.repo.model.auth.TwoFactorAuthStatus;
import org.sagebionetworks.repo.model.auth.TwoFactorState;
import org.sagebionetworks.repo.model.feature.Feature;
import org.sagebionetworks.repo.model.feature.FeatureStatus;
import org.sagebionetworks.repo.model.oauth.OAuthScope;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.util.Pair;
import org.sagebionetworks.util.SerializationUtils;
import org.sagebionetworks.util.TimeUtils;

import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.exceptions.TimeProviderException;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;

@ExtendWith(ITTestExtension.class)
public class ITTwoFactorAuthTest {
	
	private SynapseClient synapseClient;
	private CodeGenerator totpGenerator;
	private TimeProvider timeProvider;

	public ITTwoFactorAuthTest(SynapseClient synapseClient) {
		this.synapseClient = synapseClient;
		this.totpGenerator = new DefaultCodeGenerator();
		this.timeProvider = new SystemTimeProvider();
	}

	@Test
	public void testEnable2FaRoundTrip(SynapseAdminClient adminClient) throws SynapseException, JSONObjectAdapterException {
		assertEquals(TwoFactorState.DISABLED, synapseClient.get2FaStatus().getStatus());
		
		// Setup a client that uses a PAT, it should not be able to enroll, enable and disable 2FA
		String personalAccessToken = synapseClient.createPersonalAccessToken(new AccessTokenGenerationRequest()
			.setName("PAT")
			.setScope(Arrays.asList(OAuthScope.modify, OAuthScope.view))
		);
		
		SynapseClient patSynapseClient = new SynapseClientImpl();
		SynapseClientHelper.setEndpoints(patSynapseClient);
		patSynapseClient.setBearerAuthorizationToken(personalAccessToken);
		
		SynapseForbiddenException ex = assertThrows(SynapseForbiddenException.class, () -> {
			patSynapseClient.init2Fa();
		});
		
		assertEquals("insufficient_scope. Request lacks scope(s) required by this service: authorize", ex.getMessage());
		
		TotpSecret secret = synapseClient.init2Fa();
		
		ex = assertThrows(SynapseForbiddenException.class, () -> {
			patSynapseClient.enable2Fa(new TotpSecretActivationRequest()
				.setSecretId(secret.getSecretId())
				.setTotp(generateTotpCode(secret.getSecret()))
			);
		});
		
		assertEquals("insufficient_scope. Request lacks scope(s) required by this service: authorize", ex.getMessage());
		
		TwoFactorAuthStatus status = synapseClient.enable2Fa(new TotpSecretActivationRequest()
			.setSecretId(secret.getSecretId())
			.setTotp(generateTotpCode(secret.getSecret()))
		);
		
		assertEquals(TwoFactorState.ENABLED, status.getStatus());
		
		// Now generates a new secret
		TotpSecret newSecret = synapseClient.init2Fa();
		
		// 2FA is still enabled
		assertEquals(TwoFactorState.ENABLED, synapseClient.get2FaStatus().getStatus());
				
		String message = assertThrows(SynapseException.class, () -> {
			// The previous secret is already enabled
			synapseClient.enable2Fa(new TotpSecretActivationRequest()
				.setSecretId(secret.getSecretId())
				.setTotp(generateTotpCode(secret.getSecret()))
			);
		}).getMessage();
		
		assertEquals("Two factor authentication is already enabled with this secret", message);
		
		status = synapseClient.enable2Fa(new TotpSecretActivationRequest()
			.setSecretId(newSecret.getSecretId())
			.setTotp(generateTotpCode(newSecret.getSecret()))
		);
		
		assertEquals(TwoFactorState.ENABLED, status.getStatus());
		
		ex = assertThrows(SynapseForbiddenException.class, () -> {
			patSynapseClient.disable2Fa();
		});
		
		assertEquals("insufficient_scope. Request lacks scope(s) required by this service: authorize", ex.getMessage());
		
		synapseClient.disable2Fa();
		
		assertEquals(TwoFactorState.DISABLED, synapseClient.get2FaStatus().getStatus());
	}
	
	@Test
	public void testLoginWith2Fa(SynapseAdminClient adminClient) throws SynapseException, JSONObjectAdapterException {
		// Creates a new user so that we retain user/password
		SynapseClient newSynapseClient = new SynapseClientImpl();
		
		String username = UUID.randomUUID().toString();
		String password = UUID.randomUUID().toString();
		
		Long userId = SynapseClientHelper.createUser(adminClient, newSynapseClient, username, password, true, false);
		
		// First enabled 2FA
		
		TotpSecret secret = newSynapseClient.init2Fa();
		
		TwoFactorAuthStatus status = newSynapseClient.enable2Fa(new TotpSecretActivationRequest()
			.setSecretId(secret.getSecretId())
			.setTotp(generateTotpCode(secret.getSecret()))
		);
		
		assertEquals(TwoFactorState.ENABLED, status.getStatus());
		
		// Try the normal login
		LoginRequest loginRequest = new LoginRequest().setUsername(username).setPassword(password);
		
		SynapseTwoFactorAuthRequiredException twoFaResponse = assertThrows(SynapseTwoFactorAuthRequiredException.class, () -> {
			newSynapseClient.loginForAccessToken(loginRequest);
		});
		
		// Now authenticate through 2fa
		String totp = generateTotpCode(secret.getSecret());
		
		TwoFactorAuthLoginRequest twoFaLoginRequest = new TwoFactorAuthLoginRequest()
			.setUserId(twoFaResponse.getUserId())
			.setTwoFaToken(twoFaResponse.getTwoFaToken())
			.setOtpType(TwoFactorAuthOtpType.TOTP)
			.setOtpCode(totp);
		
		LoginResponse loginResponse = newSynapseClient.loginWith2Fa(twoFaLoginRequest);
		
		assertNotNull(loginResponse.getAccessToken());
		
		// The user should still be able to perform actions
		assertEquals(TwoFactorState.ENABLED, newSynapseClient.get2FaStatus().getStatus());
		
		newSynapseClient.disable2Fa();
		
		assertEquals(TwoFactorState.DISABLED, newSynapseClient.get2FaStatus().getStatus());
		
		try {
			adminClient.deleteUser(userId);
		} catch (SynapseException e) {
			
		}
	}
	
	@Test
	public void testLoginWithRecoveryCodes(SynapseAdminClient adminClient) throws SynapseException, JSONObjectAdapterException {
		// Creates a new user so that we retain user/password
		SynapseClient newSynapseClient = new SynapseClientImpl();
		
		String username = UUID.randomUUID().toString();
		String password = UUID.randomUUID().toString();
		
		Long userId = SynapseClientHelper.createUser(adminClient, newSynapseClient, username, password, true, false);
		
		// First enabled 2FA
		
		TotpSecret secret = newSynapseClient.init2Fa();
		
		TwoFactorAuthStatus status = newSynapseClient.enable2Fa(new TotpSecretActivationRequest()
			.setSecretId(secret.getSecretId())
			.setTotp(generateTotpCode(secret.getSecret()))
		);
		
		assertEquals(TwoFactorState.ENABLED, status.getStatus());
		
		// Generate a new set of recovery codes
		TwoFactorAuthRecoveryCodes recoveryCodes = newSynapseClient.generate2FaRecoveryCodes();
		
		// Try the normal login
		LoginRequest loginRequest = new LoginRequest().setUsername(username).setPassword(password);
		
		SynapseTwoFactorAuthRequiredException twoFaResponse = assertThrows(SynapseTwoFactorAuthRequiredException.class, () -> {
			newSynapseClient.loginForAccessToken(loginRequest);
		});
		
		// Try one code		
		LoginResponse loginResponse = newSynapseClient.loginWith2Fa(new TwoFactorAuthLoginRequest()
			.setUserId(twoFaResponse.getUserId())
			.setTwoFaToken(twoFaResponse.getTwoFaToken())
			.setOtpType(TwoFactorAuthOtpType.RECOVERY_CODE)
			.setOtpCode(recoveryCodes.getCodes().get(0))
		);
		
		assertNotNull(loginResponse.getAccessToken());
		
		// Regenerate a new set of codes
		recoveryCodes = newSynapseClient.generate2FaRecoveryCodes();
		
		// Now authenticate through 2fa, using all the recovery codes
		for (String recoveryCode : recoveryCodes.getCodes()) {
			TwoFactorAuthLoginRequest twoFaLoginRequest = new TwoFactorAuthLoginRequest()
					.setUserId(twoFaResponse.getUserId())
					.setTwoFaToken(twoFaResponse.getTwoFaToken())
					.setOtpType(TwoFactorAuthOtpType.RECOVERY_CODE)
					.setOtpCode(recoveryCode);
				
			loginResponse = newSynapseClient.loginWith2Fa(twoFaLoginRequest);
			
			assertNotNull(loginResponse.getAccessToken());
			
			// It should not be possible to reuse the recovery code
			SynapseUnauthorizedException ex = assertThrows(SynapseUnauthorizedException.class, () -> {				
				newSynapseClient.loginWith2Fa(twoFaLoginRequest);
			});
			
			assertEquals("The provided code is invalid.", ex.getMessage());
		}
		
		try {
			adminClient.deleteUser(userId);
		} catch (SynapseException e) {
			
		}
	}
	
	@Test
	public void testChangePasswordWith2Fa(SynapseAdminClient adminClient) throws SynapseException, JSONObjectAdapterException {
		// Creates a new user so that we retain user/password
		SynapseClient newSynapseClient = new SynapseClientImpl();
		
		String username = UUID.randomUUID().toString();
		String password = UUID.randomUUID().toString();
		
		Long userId = SynapseClientHelper.createUser(adminClient, newSynapseClient, username, password, true, false);
		
		// First enabled 2FA
		
		TotpSecret secret = newSynapseClient.init2Fa();
		
		TwoFactorAuthStatus status = newSynapseClient.enable2Fa(new TotpSecretActivationRequest()
			.setSecretId(secret.getSecretId())
			.setTotp(generateTotpCode(secret.getSecret()))
		);
		
		assertEquals(TwoFactorState.ENABLED, status.getStatus());
		
		ChangePasswordWithCurrentPassword changePasswordRequest = new ChangePasswordWithCurrentPassword()
			.setUsername(username)
			.setCurrentPassword(password)
			.setNewPassword(UUID.randomUUID().toString());
		
		// This should work initially without 2fa since the feature is still disabled
		newSynapseClient.changePassword(changePasswordRequest);
		
		// Now enable the 2fa check
		adminClient.setFeatureStatus(Feature.CHANGE_PASSWORD_2FA_CHECK_BYPASS, new FeatureStatus().setEnabled(false));
		
		changePasswordRequest
			.setCurrentPassword(changePasswordRequest.getNewPassword())
			.setNewPassword(UUID.randomUUID().toString());
		
		SynapseTwoFactorAuthRequiredException twoFaResponse = assertThrows(SynapseTwoFactorAuthRequiredException.class, () -> {
			newSynapseClient.changePassword(changePasswordRequest);
		});
		
		// Now authenticate through 2fa and change the password
		String totp = generateTotpCode(secret.getSecret());
		
		ChangePasswordWithTwoFactorAuthToken twoFaChangePasswordRequest = new ChangePasswordWithTwoFactorAuthToken()
			.setUserId(twoFaResponse.getUserId())
			.setTwoFaToken(twoFaResponse.getTwoFaToken())
			.setOtpType(TwoFactorAuthOtpType.TOTP)
			.setOtpCode(totp)
			.setNewPassword(changePasswordRequest.getNewPassword());
		
		newSynapseClient.changePassword(twoFaChangePasswordRequest);
		
		try {
			adminClient.deleteUser(userId);
		} catch (SynapseException e) {
			
		}
	}
	
	@Test
	public void testReset2FaWithToken(SynapseAdminClient adminClient) throws Exception {
		// Creates a new user so that we retain user/password
		SynapseClient newSynapseClient = new SynapseClientImpl();
		
		String username = UUID.randomUUID().toString();
		String password = UUID.randomUUID().toString();
		String email = UUID.randomUUID().toString() + "@sagebase.org";
		
		Long userId = SynapseClientHelper.createUser(adminClient, newSynapseClient, username, password, email, true, false);
		
		// First enabled 2FA
		
		TotpSecret secret = newSynapseClient.init2Fa();
		
		TwoFactorAuthStatus status = newSynapseClient.enable2Fa(new TotpSecretActivationRequest()
			.setSecretId(secret.getSecretId())
			.setTotp(generateTotpCode(secret.getSecret()))
		);
		
		assertEquals(TwoFactorState.ENABLED, status.getStatus());
		
		// Wait for the email notification for enabling two fa
		String emailS3Key = EmailValidationUtil.getBucketKeyForEmail(email);
		
		assertTrue(EmailValidationUtil.doesFileExist(emailS3Key, 10_000L));
		
		// Try the normal login
		LoginRequest loginRequest = new LoginRequest().setUsername(username).setPassword(password);
		
		SynapseTwoFactorAuthRequiredException twoFaResponse = assertThrows(SynapseTwoFactorAuthRequiredException.class, () -> {
			newSynapseClient.loginForAccessToken(loginRequest);
		});
		
		// Now enable the 2fa check
		adminClient.setFeatureStatus(Feature.CHANGE_PASSWORD_2FA_CHECK_BYPASS, new FeatureStatus().setEnabled(false));
		
		String endpoint = "https://www.synapse.org?";
		
		// Now ask to reset the 2fa with a signed token		
		newSynapseClient.send2FaResetNotification(new TwoFactorAuthResetRequest()
			.setUserId(twoFaResponse.getUserId())
			.setTwoFaToken(twoFaResponse.getTwoFaToken())
			.setTwoFaResetEndpoint(endpoint)
		);
		
		// Extracts the serialized token from the email, since various emails are sent to the user we wait until we get the email with the token
		String encodedToken = TimeUtils.waitFor(10_000, 1000, () -> {
			try {
				return Pair.create(true, EmailValidationUtil.getTokenFromFile(emailS3Key, "href=\""+endpoint, "\">"));
			} catch (AssertionError  e) {
				return Pair.create(false, null);
			}
		});
		
		
		TwoFactorAuthResetToken token = SerializationUtils.hexDecodeAndDeserialize(encodedToken, TwoFactorAuthResetToken.class);
		
		// Now authenticate again to receive a new twoFaToken to verify the first factor
		twoFaResponse = assertThrows(SynapseTwoFactorAuthRequiredException.class, () -> {
			newSynapseClient.loginForAccessToken(loginRequest);
		});
		
		newSynapseClient.disable2FaWithToken(new TwoFactorAuthDisableRequest()
			.setTwoFaToken(twoFaResponse.getTwoFaToken())
			.setTwoFaResetToken(token)
		);
		
		assertEquals(TwoFactorState.DISABLED, newSynapseClient.get2FaStatus().getStatus());
		
		try {
			adminClient.deleteUser(userId);
		} catch (SynapseException e) {
			
		}
	}
	
	private String generateTotpCode(String secret) {
		try {
			return totpGenerator.generate(secret, Math.floorDiv(timeProvider.getTime(), 30));
		} catch (TimeProviderException | CodeGenerationException e) {
			throw new RuntimeException(e);
		}
	}
	
}
