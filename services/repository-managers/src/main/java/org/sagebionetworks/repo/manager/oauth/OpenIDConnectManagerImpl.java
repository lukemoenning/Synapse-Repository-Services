package org.sagebionetworks.repo.manager.oauth;


import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.json.JSONArray;
import org.sagebionetworks.EncryptionUtilsSingleton;
import org.sagebionetworks.StackEncrypter;
import org.sagebionetworks.repo.manager.UserProfileManager;
import org.sagebionetworks.repo.model.AuthorizationUtils;
import org.sagebionetworks.repo.model.ListWrapper;
import org.sagebionetworks.repo.model.TeamDAO;
import org.sagebionetworks.repo.model.TeamMember;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.UserProfile;
import org.sagebionetworks.repo.model.auth.AuthenticationDAO;
import org.sagebionetworks.repo.model.auth.OAuthClientDao;
import org.sagebionetworks.repo.model.auth.SectorIdentifier;
import org.sagebionetworks.repo.model.oauth.OAuthAuthorizationResponse;
import org.sagebionetworks.repo.model.oauth.OAuthClient;
import org.sagebionetworks.repo.model.oauth.OAuthClientIdAndSecret;
import org.sagebionetworks.repo.model.oauth.OAuthClientList;
import org.sagebionetworks.repo.model.oauth.OAuthResponseType;
import org.sagebionetworks.repo.model.oauth.OAuthScope;
import org.sagebionetworks.repo.model.oauth.OIDCAuthorizationRequest;
import org.sagebionetworks.repo.model.oauth.OIDCAuthorizationRequestDescription;
import org.sagebionetworks.repo.model.oauth.OIDCClaimName;
import org.sagebionetworks.repo.model.oauth.OIDCClaimsRequestDetails;
import org.sagebionetworks.repo.model.oauth.OIDCTokenResponse;
import org.sagebionetworks.repo.transactions.WriteTransaction;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.schema.adapter.JSONObjectAdapter;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.JSONObjectAdapterImpl;
import org.sagebionetworks.securitytools.EncryptionUtils;
import org.sagebionetworks.securitytools.PBKDF2Utils;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.beans.factory.annotation.Autowired;

import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTClaimsSet;

import io.netty.util.internal.StringUtil;
import net.minidev.json.JSONObject;
import net.minidev.json.parser.JSONParser;

public class OpenIDConnectManagerImpl implements OpenIDConnectManager {
	private static final long AUTHORIZATION_CODE_TIME_LIMIT_MILLIS = 60000L; // one minute

	// see https://openid.net/specs/openid-connect-core-1_0.html#ClaimsParameter
	private static final String ID_TOKEN_CLAIMS_KEY = "id_token";
	private static final String USER_INFO_CLAIMS_KEY = "userinfo";
	
	private StackEncrypter stackEncrypter = EncryptionUtilsSingleton.singleton();
	
	@Autowired
	private OAuthClientDao oauthClientDao;
	
	@Autowired
	private AuthenticationDAO authDao;
	
	@Autowired
	private UserProfileManager userProfileManager;
	
	
	@Autowired
	private TeamDAO teamDAO;
	
	@WriteTransaction
	@Override
	public OAuthClient createOpenIDConnectClient(UserInfo userInfo, OAuthClient oauthClient) {
		if (AuthorizationUtils.isUserAnonymous(userInfo)) { // TODO move to authorizationManager
			throw new UnauthorizedException("Anonymous user may not create an OAuth Client");
		}
		// TODO validation, esp. sector identifier!!!
		oauthClient.setCreatedBy(userInfo.getId().toString());
		oauthClient.setValidated(false);
		// find or create SectorIdentifier
		if (!oauthClientDao.doesSectorIdentifierExistForURI(oauthClient.getSector_identifier())) {
			SectorIdentifier sectorIdentifier = new SectorIdentifier();
			sectorIdentifier.setCreatedBy(userInfo.getId());
			sectorIdentifier.setCreatedOn(System.currentTimeMillis());
			String sectorIdentifierSecret = EncryptionUtils.newSecretKey();
			String encryptedSISecret = stackEncrypter.encryptAndBase64EncodeStringWithStackKey(sectorIdentifierSecret);
			sectorIdentifier.setSecret(encryptedSISecret);
			sectorIdentifier.setSectorIdentifierUri(oauthClient.getSector_identifier());
			oauthClientDao.createSectorIdentifier(sectorIdentifier);
		}
		
		return oauthClientDao.createOAuthClient(oauthClient);
	}

	@Override
	public OAuthClient getOpenIDConnectClient(UserInfo userInfo, String id) {
		OAuthClient result = oauthClientDao.getOAuthClient(id);
		if (!result.getCreatedBy().equals(userInfo.getId().toString()) && !userInfo.isAdmin()) {
			throw new UnauthorizedException("You can only retrieve your own OAuth client(s).");
		}
		return result;
	}

	@Override
	public OAuthClientList listOpenIDConnectClients(UserInfo userInfo, String nextPageToken) {
		return oauthClientDao.listOAuthClients(nextPageToken, userInfo.getId());
	}

	@WriteTransaction
	@Override
	public OAuthClient updateOpenIDConnectClient(UserInfo userInfo, OAuthClient oauthClient) {
		// TODO validation, esp. sector identifier!!!
		oauthClient.setValidated(null); // null means not to change the current state in the back end, another process will set this flag
		OAuthClient updated = oauthClientDao.updateOAuthClient(oauthClient);
		if (!updated.getCreatedBy().equals(userInfo.getId().toString()) && !userInfo.isAdmin()) {
			throw new UnauthorizedException("You can only update your own OAuth client(s).");
		}
		return updated;
	}

	@WriteTransaction
	@Override
	public void deleteOpenIDConnectClient(UserInfo userInfo, String id) {
		String creator = oauthClientDao.getOAuthClientCreator(id);
		if (!creator.equals(userInfo.getId().toString()) && !userInfo.isAdmin()) {
			throw new UnauthorizedException("You can only delete your own OAuth client(s).");
		}
		oauthClientDao.deleteOAuthClient(id);
	}
	
	private static final Random RANDOM = new SecureRandom();
	
	public static String generateOAuthClientSecret() {
		byte[] randomBytes = new byte[32];
		RANDOM.nextBytes(randomBytes);
		return Base64.getUrlEncoder().encodeToString(randomBytes);
	}

	@WriteTransaction
	@Override
	public OAuthClientIdAndSecret createClientSecret(UserInfo userInfo, String clientId) {
		String creator = oauthClientDao.getOAuthClientCreator(clientId);
		if (!creator.equals(userInfo.getId().toString()) && !userInfo.isAdmin()) {
			throw new UnauthorizedException("You can only generate credentials for your own OAuth client(s).");
		}		
		String secret = generateOAuthClientSecret();
		String secretHash = PBKDF2Utils.hashPassword(secret, null);
		oauthClientDao.setOAuthClientSecretHash(clientId, secretHash);
		OAuthClientIdAndSecret result = new OAuthClientIdAndSecret();
		result.setClientId(clientId);
		result.setClientSecret(secret);
		return result;
	}
	/*
	 * The scope parameter in an OAuth authorization request is a space-delimted list of scope values.
	 */
	private static List<OAuthScope> parseScopeString(String s) {
		StringTokenizer st = new StringTokenizer(s);
		List<OAuthScope> result = new ArrayList<OAuthScope>();
		while (st.hasMoreTokens()) {
			result.add(OAuthScope.valueOf(st.nextToken())); // TODO verify this throws Illegal argument exception if scope is not from enum
		}
		return result;
	}
	
	private static Map<OIDCClaimName,String> CLAIM_DESCRIPTION;
	static {
		CLAIM_DESCRIPTION = new HashMap<OIDCClaimName,String>();
		CLAIM_DESCRIPTION.put(OIDCClaimName.team, "Your team membership");
		CLAIM_DESCRIPTION.put(OIDCClaimName.family_name, "Your last name, if you share it with Synapse"); // https://openid.net/specs/openid-connect-core-1_0.html#StandardClaims
		CLAIM_DESCRIPTION.put(OIDCClaimName.given_name, "Your first name, if you share it with Synapse"); // https://openid.net/specs/openid-connect-core-1_0.html#StandardClaims
		CLAIM_DESCRIPTION.put(OIDCClaimName.email, "Your email address (<username>@synapse.org)");
		CLAIM_DESCRIPTION.put(OIDCClaimName.email_verified, "Your email address (<username>@synapse.org)");
		CLAIM_DESCRIPTION.put(OIDCClaimName.company, "Your company, if you share it with Synapse");
		CLAIM_DESCRIPTION.put(OIDCClaimName.auth_time, "The time when you last logged in to Synapse.");
	}
	
	public static void validateAuthenticationRequest(OIDCAuthorizationRequest authorizationRequest, OAuthClient client) {
		ValidateArgument.validUrl(authorizationRequest.getRedirectUri(), "Redirect URI");
		if (!client.getRedirect_uris().contains(authorizationRequest.getRedirectUri())) {
			throw new IllegalArgumentException("Redirect URI "+authorizationRequest.getRedirectUri()+
					" is not registered for "+client.getClient_name());
		}		
		if (authorizationRequest.getResponseType()!=OAuthResponseType.code) 
			throw new IllegalArgumentException("Unsupported response type "+authorizationRequest.getResponseType());
	}
	
	private static final JSONParser MINIDEV_JSON_PARSER = new JSONParser(JSONParser.MODE_JSON_SIMPLE);

	public static Map<OIDCClaimName,OIDCClaimsRequestDetails> getClaimsMapFromClaimsRequestParam(String claims, String claimsField) {
		JSONObject claimsObject;
		try {
			claimsObject = (JSONObject)MINIDEV_JSON_PARSER.parse(claims);
		} catch (net.minidev.json.parser.ParseException e) {
			throw new IllegalArgumentException(e);
		}
		JSONObject idTokenClaims = (JSONObject)claimsObject.get(claimsField);
		return ClaimsJsonUtil.getClaimsMapFromJSONObject(idTokenClaims, /*ignoreUnknownClaims*/true);
	}

	@Override
	public OIDCAuthorizationRequestDescription getAuthenticationRequestDescription(
			OIDCAuthorizationRequest authorizationRequest) {
		
		ValidateArgument.required(authorizationRequest, "Authorization Request");
		ValidateArgument.requiredNotEmpty(authorizationRequest.getClientId(), "Client ID");
		OAuthClient client;
		try {
			client = oauthClientDao.getOAuthClient(authorizationRequest.getClientId());
		} catch (NotFoundException e) {
			throw new IllegalArgumentException("Invalid OAuth Client ID: "+authorizationRequest.getClientId());
		}
		validateAuthenticationRequest(authorizationRequest, client);
		OIDCAuthorizationRequestDescription result = new OIDCAuthorizationRequestDescription();
		result.setClient_name(client.getClient_name());
		result.setClient_uri(client.getClient_uri());
		result.setPolicy_uri(client.getPolicy_uri());
		result.setTos_uri(client.getTos_uri());
		result.setIs_verified(client.getValidated());
		result.setRedirect_uri(authorizationRequest.getRedirectUri());

		List<OAuthScope> scopes = parseScopeString(authorizationRequest.getScope());
		Set<String> scopeDescriptions = new TreeSet<String>();
		for (OAuthScope scope : scopes) {
			String scopeDescription = null;
			switch (scope) {
			case openid:
				// required for OIDC requests.  Doesn't add anything to what access is requested
				break;
			default:
				// unrecognized scope values should be ignored https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest
				break;
			}
			if (StringUtils.isNotEmpty(scopeDescription)) {
				scopeDescriptions.add(scopeDescription);
			}
		}
		
		// Use of [the OpenID Connect] extension [to OAuth 2.0] is requested by Clients by including the openid scope value in the Authorization Request.
		// https://openid.net/specs/openid-connect-core-1_0.html#Introduction
		if (scopes.contains(OAuthScope.openid) && !StringUtil.isNullOrEmpty(authorizationRequest.getClaims())) {
			{
				Map<OIDCClaimName,OIDCClaimsRequestDetails> idTokenClaimsMap = 
						getClaimsMapFromClaimsRequestParam(authorizationRequest.getClaims(), ID_TOKEN_CLAIMS_KEY);
				for (OIDCClaimName claim : idTokenClaimsMap.keySet()) {
					scopeDescriptions.add(CLAIM_DESCRIPTION.get(claim));
				}
			}
			{
				Map<OIDCClaimName,OIDCClaimsRequestDetails> userInfoClaimsMap = 
						getClaimsMapFromClaimsRequestParam(authorizationRequest.getClaims(), USER_INFO_CLAIMS_KEY);
				for (OIDCClaimName claim : userInfoClaimsMap.keySet()) {
					scopeDescriptions.add(CLAIM_DESCRIPTION.get(claim));
				}
			}
		}
		result.setScope(new ArrayList<String>(scopeDescriptions));
		return result;
	}
	
	@Override
	public OAuthAuthorizationResponse authorizeClient(UserInfo userInfo,
			OIDCAuthorizationRequest authorizationRequest) {
		if (AuthorizationUtils.isUserAnonymous(userInfo)) {
			throw new UnauthorizedException("Anonymous users may not provide access to OAuth clients.");
		}

		// 	when evaluating the claims object, how do we differentiate between a null value and a missing key?
		// 	They mean different things https://openid.net/specs/openid-connect-core-1_0.html#ClaimsParameter
		OAuthClient client;
		try {
			client = oauthClientDao.getOAuthClient(authorizationRequest.getClientId());
		} catch (NotFoundException e) {
			throw new IllegalArgumentException("Invalid OAuth Client ID: "+authorizationRequest.getClientId());
		}
		validateAuthenticationRequest(authorizationRequest, client);
		 
		authorizationRequest.setUserId((new Long(userInfo.getId()).toString()));
		authorizationRequest.setAuthorizedAt((new Date(System.currentTimeMillis())));
		authorizationRequest.setAuthenticatedAt(authDao.getSessionValidatedOn(userInfo.getId()));
		
		JSONObjectAdapter adapter = new JSONObjectAdapterImpl();
		try {
			authorizationRequest.writeToJSONObject(adapter);
		} catch (JSONObjectAdapterException e) {
			throw new RuntimeException(e);
		}
		String serializedAuthorizationRequest = adapter.toJSONString();
		String encryptedAuthorizationRequest = stackEncrypter.encryptAndBase64EncodeStringWithStackKey(serializedAuthorizationRequest);
				
		OAuthAuthorizationResponse result = new OAuthAuthorizationResponse();
		result.setAccess_code(encryptedAuthorizationRequest);
		return result;
	}
	
	// As per, https://openid.net/specs/openid-connect-core-1_0.html#PairwiseAlg
	public String ppid(String userId, String clientId) {
		String encryptedSISecret = oauthClientDao.getSectorIdentifierSecretForClient(clientId);
		String sectorIdentifierSecret = stackEncrypter.decryptStackEncryptedAndBase64EncodedString(encryptedSISecret);
		return EncryptionUtils.encrypt(userId, sectorIdentifierSecret);
	}
	
	public  String getUserIdFromPPID(String ppid, String clientId) {
		String encryptedSISecret = oauthClientDao.getSectorIdentifierSecretForClient(clientId);
		String sectorIdentifierSecret = stackEncrypter.decryptStackEncryptedAndBase64EncodedString(encryptedSISecret);
		return EncryptionUtils.decrypt(ppid, sectorIdentifierSecret);
	}
	
	/*
	 * Given the scopes and additional OIDC claims requested by the user, return the 
	 * user info claims to add to the returned User Info object or JSON Web Token
	 */
	public Map<OIDCClaimName,String> getUserInfo(String userId, List<OAuthScope> scopes, Map<OIDCClaimName, OIDCClaimsRequestDetails>  oidcClaims) {
		Map<OIDCClaimName,String> result = new HashMap<OIDCClaimName,String>();
		// Use of [the OpenID Connect] extension [to OAuth 2.0] is requested by Clients by including the openid scope value in the Authorization Request.
		// https://openid.net/specs/openid-connect-core-1_0.html#Introduction
		if (!scopes.contains(OAuthScope.openid)) return result;
		
		UserProfile privateUserProfile = userProfileManager.getUserProfile(userId);
		
		for (OIDCClaimName claimName : oidcClaims.keySet()) {
			OIDCClaimsRequestDetails claimsDetails = oidcClaims.get(claimName);
			String claimValue = null;
			switch (claimName) {
			case email:
			case email_verified:
				claimValue = privateUserProfile.getUserName()+"@synapse.org";
				break;
			case given_name:
				claimValue = privateUserProfile.getFirstName();
				break;
			case family_name:
				claimValue = privateUserProfile.getLastName();
				break;
			case company:
				claimValue = privateUserProfile.getCompany();
				break;
			case team:
				Set<String> requestedTeamIds = new HashSet<String>();
				if (StringUtils.isNotEmpty(claimsDetails.getValue())) {
					requestedTeamIds.add(claimsDetails.getValue());
				}
				if (!claimsDetails.getValues().isEmpty()) {
					requestedTeamIds.addAll(requestedTeamIds);
				}
				Set<String> memberTeamIds = getMemberTeamIds(userId, requestedTeamIds);
				claimValue = asSerializedJSON(memberTeamIds);
				break;
			case userid:
				claimValue = userId;
				break;
			default:
				continue;

			}
			result.put(claimName, claimValue);
		}
		return result;
	}
	
	/*
	 * return the subset of team Ids in which the given user is a member
	 */
	private Set<String> getMemberTeamIds(String userId, Set<String> requestedTeamIds) {
		List<Long> numericTeamIds = new ArrayList<Long>();
		for (String stringTeamId : requestedTeamIds) {
			try {
				numericTeamIds.add(Long.parseLong(stringTeamId));
			} catch (NumberFormatException e) {
				// this will be translated into a 400 level status, sent back to the client
				throw new IllegalArgumentException(stringTeamId+" is not a valid Team ID");
			}
		}
		ListWrapper<TeamMember> teamMembers = teamDAO.listMembers(numericTeamIds, Collections.singletonList(Long.parseLong(userId)));
		Set<String> result = new HashSet<String>();
		for (TeamMember teamMember : teamMembers.getList()) {
			result.add(teamMember.getTeamId());
		}
		return result;
	}
	
	public static String asSerializedJSON(Collection<?> c) {
		JSONArray array = new JSONArray();
		for (Object s : c) {
			array.put(s.toString());
		}
		return array.toString();
	}

	@Override
	public OIDCTokenResponse getAccessToken(String code, String verifiedClientId, String redirectUri, String oauthEndpoint) {
		String serializedAuthorizationRequest;
		try {
			serializedAuthorizationRequest = stackEncrypter.decryptStackEncryptedAndBase64EncodedString(code);
		} catch (Exception e) {
			throw new IllegalArgumentException("Invalid authorization code: "+code, e);
		}
		OIDCAuthorizationRequest authorizationRequest = new OIDCAuthorizationRequest();
		try {
			JSONObjectAdapter adapter = new JSONObjectAdapterImpl(serializedAuthorizationRequest);
			authorizationRequest.initializeFromJSONObject(adapter);
		} catch (JSONObjectAdapterException e) {
			throw new IllegalArgumentException("Incorrectly formatted authorization code: "+code, e);
		}
		
		// enforce expiration of authorization code
		long now = System.currentTimeMillis();
		if (System.currentTimeMillis() > authorizationRequest.getAuthorizedAt().getTime()+AUTHORIZATION_CODE_TIME_LIMIT_MILLIS) {
			throw new IllegalArgumentException("Authorization code has expired.");
		}
		
		// ensure redirect URI matches
		if (!authorizationRequest.getRedirectUri().equals(redirectUri)) {
			throw new IllegalArgumentException("URI mismatch: "+authorizationRequest.getRedirectUri()+" vs. "+redirectUri);
		}
		
		List<OAuthScope> scopes = parseScopeString(authorizationRequest.getScope());
				
		Long authTimeSeconds = null;
		if (authorizationRequest.getAuthenticatedAt()!=null) {
			authTimeSeconds = authorizationRequest.getAuthenticatedAt().getTime()/1000L;
		}
		
		// The following implements 'pairwise' subject_type, https://openid.net/specs/openid-connect-discovery-1_0.html#ProviderConfigurationResponse
		// Pairwise Pseudonymous Identifier (PPID)
		String ppid = ppid(authorizationRequest.getUserId(), verifiedClientId);
		String oauthClientId = authorizationRequest.getClientId();
		
		OIDCTokenResponse result = new OIDCTokenResponse();
		
		// Use of [the OpenID Connect] extension [to OAuth 2.0] is requested by Clients by including the openid scope value in the Authorization Request.
		// https://openid.net/specs/openid-connect-core-1_0.html#Introduction
		if (scopes.contains(OAuthScope.openid)) {
			String idTokenId = UUID.randomUUID().toString();
			Map<OIDCClaimName,String> userInfo = getUserInfo(authorizationRequest.getUserId(), 
					scopes, getClaimsMapFromClaimsRequestParam(authorizationRequest.getClaims(), ID_TOKEN_CLAIMS_KEY));
			String idToken = OIDCTokenUtil.createOIDCIdToken(oauthEndpoint, ppid, oauthClientId, now, 
				authorizationRequest.getNonce(), authTimeSeconds, idTokenId, userInfo);
			result.setId_token(idToken);
		}
		
		String accessTokenId = UUID.randomUUID().toString();
		String accessToken = OIDCTokenUtil.createOIDCaccessToken(oauthEndpoint, ppid, 
				oauthClientId, now, authTimeSeconds, accessTokenId, scopes, 
				getClaimsMapFromClaimsRequestParam(authorizationRequest.getClaims(), USER_INFO_CLAIMS_KEY));
		result.setAccess_token(accessToken);
		return result;
	}

	@Override
	public Object getUserInfo(JWT accessToken, String oauthEndpoint) {
		try {
			JWTClaimsSet accessTokenClaimsSet = accessToken.getJWTClaimsSet();
			// We set exactly one Audience when creating the token
			if (accessTokenClaimsSet.getAudience()==null || accessTokenClaimsSet.getAudience().size()!=1) {
				throw new IllegalArgumentException("Expected exactly one Audience value in the OAuth Access Token but found "+
						accessTokenClaimsSet.getAudience());
			}
			String oauthClientId = accessTokenClaimsSet.getAudience().get(0); 
			OAuthClient oauthClient = oauthClientDao.getOAuthClient(oauthClientId);
			
			String ppid = accessTokenClaimsSet.getSubject();
			Long authTimeSeconds = accessTokenClaimsSet.getLongClaim(OIDCClaimName.auth_time.name());
			
			// userId is used to retrieve the user info
			String userId = getUserIdFromPPID(ppid, oauthClientId);
			
			List<OAuthScope> scopes = OIDCTokenUtil.getScopeFromClaims(accessTokenClaimsSet);
			Map<OIDCClaimName, OIDCClaimsRequestDetails>  oidcClaims = OIDCTokenUtil.getOIDCClaimsFromClaimSet(accessTokenClaimsSet);
			
			Map<OIDCClaimName,String> userInfo = getUserInfo(userId, scopes, oidcClaims);

			// From https://openid.net/specs/openid-connect-registration-1_0.html#ClientMetadata
			// "If this is specified, the response will be JWT serialized, and signed using JWS. 
			// The default, if omitted, is for the UserInfo Response to return the Claims as a UTF-8 
			// encoded JSON object using the application/json content-type."
			// 
			// Note: This leaves ambiguous what to do if the client is registered with a signing algorithm
			// and then sends a request with Accept: application/json or vice versa (registers with no 
			// algorithm and then sends a request with Accept: application/jwt).
			boolean returnJson = oauthClient.getUserinfo_signed_response_alg()==null;
			
			if (returnJson) {
				// https://openid.net/specs/openid-connect-core-1_0.html#UserInfoResponse
				userInfo.put(OIDCClaimName.sub, ppid);
				return userInfo;
			} else {
				long now = System.currentTimeMillis();
				String jwtIdToken = OIDCTokenUtil.createOIDCIdToken(oauthEndpoint, ppid, oauthClientId, now, null,
						authTimeSeconds, UUID.randomUUID().toString(), userInfo);
				
				return jwtIdToken;
			}
		} catch (java.text.ParseException e) {
			throw new RuntimeException(e);
		}

	}

}
