
# JWT Identity Integration — Security Requirements

**Project:** UniCloudAutoJudge  
**Owner:** Security Engineer  
**Status:** Integration proposal; external identity contract requires agreement.

---

## 1. Purpose

This document defines the security requirements for integrating the Judge API with a shared identity provider, university portal or another authenticated project service.

The requirements are not specific to one team.

Any service that issues JWT access tokens for use by the Judge API must provide an agreed identity contract.

---

## 2. Current API authentication

The current API uses Spring Security with stateless Bearer-token authentication.

The existing implementation:

- Issues local RS256 JWTs.
- Loads RSA keys from environment variables.
- Validates the JWT signature.
- Checks issuer and audience.
- Validates expiration and issued-at time.
- Uses `LocalUserJwtConverter`.
- Expects the subject (`sub`) to identify a local user by UUID.
- Checks whether the local account is enabled.
- Checks the `token_version` claim.
- Derives authorities from the local database role.

The current API therefore uses a local identity model.

An external Portal JWT is not automatically compatible with this implementation.

---

## 3. Requirements for an external identity provider

Before integrating an external identity provider, the following information must be agreed.

| Requirement | Question |
|---|---|
| Issuer | What is the exact `iss` value? |
| JWKS | What is the HTTPS JWKS URL? |
| Audience | What is the exact `aud` value for the Judge API? |
| Algorithm | Is RS256 required? |
| Subject | What is the stable `sub` format? |
| Roles | Which claim contains roles or permissions? |
| Role mapping | How are external roles mapped to local permissions? |
| Token lifetime | How long is an access token valid? |
| Clock skew | What clock difference is allowed? |
| Key rotation | How are signing keys rotated? |
| Account status | How are disabled users handled? |
| Revocation | How are revoked sessions or permissions handled? |

These values must be confirmed before changing the existing authentication implementation.

---

## 4. Proposed integration flow

1. A user authenticates with the agreed identity provider.
2. The identity provider issues a signed access token.
3. The user sends the Bearer token to the Judge API.
4. The API validates the token signature using trusted public keys.
5. The API checks the issuer and audience.
6. The API validates expiration and other required time claims.
7. The API resolves the stable user identity.
8. The API maps roles and permissions according to the agreed policy.
9. The API enforces local authorization rules.
10. The worker uses a separate service identity rather than a student's JWT.

The Judge API must never receive or store the identity provider's private signing key.

---

## 5. JWKS security requirements

If the identity provider supplies a JWKS endpoint:

- Use HTTPS.
- Trust only the agreed JWKS URL.
- Validate the token signature.
- Accept only the agreed signing algorithm.
- Validate issuer and audience.
- Reject expired tokens.
- Reject tokens with missing required claims.
- Support key rotation safely.
- Never accept arbitrary public keys supplied by a client.
- Never accept unsigned tokens or `alg=none`.

The API should handle key rotation using the provider's published key identifiers (`kid`).

Old keys should remain available during an agreed transition period so that valid tokens are not rejected unexpectedly.

---

## 6. Local user and role mapping

The project must decide whether:

1. External identity is mapped to local PostgreSQL users and roles; or
2. Trusted signed role claims are used directly.

This is an authorization decision and must not be changed without agreement.

The following must be documented:

- Stable external user identifier.
- Local user mapping.
- Student, teacher and administrator roles.
- Disabled account behavior.
- Role changes.
- Token revocation behavior.
- Worker service identity.

A worker must never reuse a student's access token.

---

## 7. Integration test requirements

Before production integration, test:

| Test | Expected result |
|---|---|
| Valid signature and correct issuer/audience | Accepted |
| Invalid signature | Rejected |
| Incorrect issuer | Rejected |
| Incorrect audience | Rejected |
| Expired token | Rejected |
| Missing subject | Rejected |
| Unknown user | Rejected according to agreed policy |
| Invalid role | Rejected |
| Unauthorized role | HTTP 403 |
| Signing-key rotation | Valid new key accepted |

Tests must use disposable test credentials and a test identity provider.

Do not use production signing keys in local integration tests.

---

## 8. Questions for the identity provider owner

1. What is the exact issuer URL?
2. What is the HTTPS JWKS endpoint?
3. What audience should the Judge API expect?
4. Which signing algorithm is used?
5. What is the subject format?
6. Which claim contains roles?
7. What is the role vocabulary?
8. How are disabled accounts handled?
9. How are revoked sessions handled?
10. What is the access-token lifetime?
11. How is signing-key rotation performed?
12. Can a test issuer and test accounts be provided?

---

## 9. Message for other project teams

Здравейте!

От страна на Security на UniCloudAutoJudge искаме да уточним договора за интеграция на JWT идентичността между услугите.

За да интегрираме правилно общата автентикация с нашия Spring Security API, трябва да уточним `iss`, `aud`, HTTPS JWKS URL, формата на `sub`, claim-а за роли и permissions, срока на токена и механизма за ротация на ключовете.

Важно е също да уточним как се обработват деактивирани потребители, отнети роли и отнети сесии.

Можем да направим първоначални директни API тестове с тестов issuer/JWKS и примерен подписан JWT, преди всички потребителски интерфейси да са готови.

Worker услугата трябва да използва отделна service identity, а не JWT токен на студент.

Благодаря!

---

## 10. Current status

The existing local JWT implementation is present and must not be replaced without an agreed integration contract.

External identity integration remains subject to coordination and testing with the relevant service owners.
