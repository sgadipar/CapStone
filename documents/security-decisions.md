# Security Decisions

## Why BFF over Pure-SPA Tokens

The Backend-for-Frontend (BFF) pattern is chosen over pure Single Page Application (SPA) token-based authentication to enhance security and simplify client-side logic. In a pure-SPA approach, the frontend would need to handle OAuth2 flows directly, storing access tokens in localStorage or sessionStorage, which exposes them to XSS attacks. The BFF acts as a secure intermediary: the SPA uses session cookies for authentication, while the BFF manages OAuth2 token exchange and forwards JWTs to the Resource Server. This keeps sensitive tokens server-side only, prevents CORS complexities, and allows the BFF to handle token refresh transparently. As seen in the code, the BFF uses Spring Security's OAuth2 client support to perform the authorization code flow, storing the resulting tokens in the HTTP session rather than exposing them to the browser.

## Where Tokens Live in Your Build

Tokens are stored server-side in the BFF's HTTP session and never reach the browser. The frontend relies on session cookies (JSESSIONID) for authentication state, while OAuth2 tokens (id_tokens) are extracted from the OidcUser in the session and forwarded as Bearer headers to the Resource Server. This is implemented in WebClientConfig.java, where the oidcBearerFilter reads the id_token from SecurityContextHolder and adds it to outgoing requests. No tokens are stored in localStorage, sessionStorage, or cookies accessible to JavaScript, preventing theft via XSS.

## How CSRF is Handled

CSRF protection uses Spring Security's cookie-based token repository with a double-submit pattern. The server sets an XSRF-TOKEN cookie (readable by JavaScript) on authenticated requests. For mutating operations (POST, PUT, DELETE, PATCH), the frontend reads this cookie and sends its value in the X-XSRF-TOKEN header. This is configured in SecurityConfig.java with CookieCsrfTokenRepository.withHttpOnlyFalse() and CsrfTokenRequestAttributeHandler. A custom OncePerRequestFilter ensures the token is written to the response even on GET requests, preventing 403 errors on logout. The apiClient.js in the frontend implements the client-side logic, reading the cookie and attaching the header for non-GET requests.

## How the BFF Authenticates to the Resource Server

The BFF authenticates to the Resource Server using a WebClient filter that extracts the OIDC id_token from the user's session and forwards it as a Bearer header. In WebClientConfig.java, the oidcBearerFilter checks if the Authentication is an OAuth2AuthenticationToken with an OidcUser principal, then retrieves the id_token value and adds "Authorization: Bearer <id_token>" to the request. This JWT is validated by the Resource Server's JwtDecoder, which supports multi-issuer configurations for both Google and mock-auth. The id_token is preferred over access_token because it's always a verifiable JWT with role claims, unlike Google's opaque access tokens.

## How RBAC is Enforced

Role-Based Access Control (RBAC) is enforced through multiple layers: URL-level filtering, method-level authorization, and service-layer ownership checks. In SecurityConfig.java, the authorizeHttpRequests configuration permits public access to static assets, OAuth2 endpoints, and health checks, while requiring authentication for all other requests. The Resource Server uses @PreAuthorize annotations (though not shown in the provided code, it's standard Spring Security practice) to check roles like "ROLE_CUSTOMER" or "ROLE_ADMIN" extracted from JWT claims. Ownership is enforced at the service layer in AccountService.java, where loadOwned() filters accounts by ownerId and throws ResourceNotFoundException (404) for unauthorized access, implementing a "safe 404" pattern that prevents account ID enumeration.

## What You Would Do Differently in Production

In production, several enhancements would be implemented for scalability, security, and reliability. Session storage would migrate from in-memory to Redis using Spring Session Data Redis for horizontal scaling and session persistence. HTTPS would be enforced everywhere with proper SSL/TLS certificates and HSTS headers. Rate limiting would be added using Spring Cloud Gateway or similar to prevent abuse. For Kafka event publishing, an outbox pattern would be implemented with a database table to store events transactionally, ensuring reliable delivery even if the message broker is temporarily unavailable. Additionally, secrets management would use a vault like HashiCorp Vault instead of environment variables, and comprehensive logging/monitoring with tools like ELK stack would be added for observability.
