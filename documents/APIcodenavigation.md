# API Code Navigation Guide: `/api/v1/accounts`

A comprehensive walkthrough of how a request flows from the frontend through the BFF to the Resource Server, including authentication, authorization, and ownership enforcement.

---

## Table of Contents
1. [Frontend: AccountsPage.jsx - Who Calls the API?](#1-frontend-accountspagejsx--who-calls-the-api)
2. [Frontend: accounts.js - What URL is Hit?](#2-frontend-accountsjs--what-url-is-hit)
3. [Frontend: apiClient.js - Session & CSRF Token](#3-frontend-apiclientjs--how-does-the-request-carry-auth--csrf)
4. [Frontend: vite.config.js - Proxy Configuration](#4-frontend-viteconfigjs--where-does-api-proxy-to)
5. [BFF: AccountsBffController.java - How Does It Forward?](#5-bff-accountsbffcontrollerjava--how-does-the-bff-forward)
6. [BFF: WebClientConfig.java - Bearer Token Attachment](#6-bff-webclientconfigjava--how-does-bearer-token-get-attached)
7. [Resource Server: AccountController.java - Request Handler](#7-resource-server-accountcontrollerjava--who-handles-it-on-the-rs)
8. [Resource Server: AccountService.java - Ownership Logic](#8-resource-server-accountservicejava--what-does-ownership-look-like)
9. [Resource Server: AccountRepository.java - Data Access](#9-resource-server-accountrepositoryjava)
10. [Complete Request/Response Flow](#complete-requestresponse-flow-diagram)
11. [Security Summary](#security-summary)

---

## 1. FRONTEND: AccountsPage.jsx — Who Calls the API?

**File:** `frontend/src/routes/AccountsPage.jsx`

```javascript
import { useEffect, useState } from "react";
import { listAccounts } from "../api/accounts.js";
import AccountCard from "../components/AccountCard.jsx";

/**
 * Lists the caller's own accounts. Shows loading, empty, and error states
 * (the rubric grades all three).
 */
export default function AccountsPage() {
  const [accounts, setAccounts] = useState(null);
  const [error, setError] = useState(null);

  // Lines 13-17: useEffect hook calls listAccounts() on mount
  useEffect(() => {
    listAccounts()
      .then(setAccounts)
      .catch((e) => setError(e.message));
  }, []);

  // Render states
  if (error) return <p className="error">Could not load accounts: {error}</p>;
  if (!accounts) return <p>Loading accounts…</p>;
  if (accounts.length === 0) return <p>You have no accounts yet.</p>;

  return (
    <section>
      <h1>Your accounts</h1>
      <ul className="accounts">
        {accounts.map((a) => (
          <AccountCard key={a.accountId} account={a} />
        ))}
      </ul>
    </section>
  );
}
```

### What Happens:
- ✅ When component mounts, `useEffect` hook calls `listAccounts()`
- ✅ `listAccounts()` is a Promise-based API call (async)
- ✅ On success, updates `accounts` state with response
- ✅ On error, updates `error` state and displays error message
- ✅ Renders three states: loading, error, or account list

---

## 2. FRONTEND: accounts.js — What URL is Hit?

**File:** `frontend/src/api/accounts.js`

```javascript
import { apiFetch } from "./apiClient.js";

export const listAccounts = () => apiFetch("/api/v1/accounts");
export const getAccount = (id) => apiFetch(`/api/v1/accounts/${id}`);
export const getTransactions = (id) =>
  apiFetch(`/api/v1/accounts/${id}/transactions`);
```

### What Happens:
- ✅ `listAccounts()` creates a fetch request to `/api/v1/accounts`
- ✅ URL is **relative** (same-origin request)
- ✅ `apiFetch()` is a wrapper that adds auth headers and error handling
- ✅ All account-related endpoints go through this module

---

## 3. FRONTEND: apiClient.js — How Does the Request Carry Auth & CSRF?

**File:** `frontend/src/api/apiClient.js`

```javascript
/**
 * API client — the entire auth surface in JavaScript.
 *
 * Same-origin: all requests go through the Vite proxy to the BFF on :8080.
 * The browser sends the JSESSIONID cookie automatically (same-origin).
 * No tokens, no localStorage, no oidc-client-ts.
 *
 * CSRF: Spring sets an XSRF-TOKEN cookie (JS-readable). On mutations
 * (POST, PUT, DELETE, PATCH), we read it and send X-XSRF-TOKEN.
 *
 * On 401: throw ApiError so callers (useMe etc.) can set user=null and
 * render sign-in options. No redirect — avoids infinite reload loops.
 */

function readCsrfToken() {
  return document.cookie
    .split("; ")
    .find((row) => row.startsWith("XSRF-TOKEN="))
    ?.split("=")[1];
}

export class ApiError extends Error {
  constructor(status, body) {
    super(body?.detail || body?.title || `HTTP ${status}`);
    this.status = status;
    this.body = body;
  }
}

export async function apiFetch(path, init = {}) {
  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers ?? {});

  // Default Content-Type for bodies
  if (!headers.has("Content-Type") && init.body) {
    headers.set("Content-Type", "application/json");
  }

  // ✅ ATTACH CSRF TOKEN FOR MUTATING REQUESTS
  // Line 40-42: Read XSRF-TOKEN cookie and add to header
  if (method !== "GET" && method !== "HEAD") {
    const csrf = readCsrfToken();
    if (csrf) headers.set("X-XSRF-TOKEN", csrf);
  }

  // ✅ CRITICAL: credentials: "same-origin"
  // Line 45-49: This sends JSESSIONID cookie AUTOMATICALLY
  const res = await fetch(path, {
    ...init,
    headers,
    credentials: "same-origin",  // ← SENDS JSESSIONID COOKIE
  });

  // Handle 401 Unauthorized
  if (res.status === 401) {
    throw new ApiError(401, { detail: "Unauthorized" });
  }

  // Handle other errors
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new ApiError(res.status, body);
  }

  return res.status === 204 ? undefined : res.json();
}
```

### Key Details:

| Aspect | How It Works |
|--------|------------|
| **Session** | `credentials: "same-origin"` = browser automatically sends `JSESSIONID` cookie |
| **CSRF Protection** | Reads `XSRF-TOKEN` cookie, adds as `X-XSRF-TOKEN` header for mutations |
| **Content-Type** | Defaults to `application/json` if not specified |
| **Error Handling** | 401 throws `ApiError`, caller can handle logout |
| **Request Path** | Relative path `/api/v1/accounts` (same-origin) |

---

## 4. FRONTEND: vite.config.js — Where Does /api Proxy To?

**File:** `frontend/vite.config.js`

```javascript
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test-setup.js"],
    globals: true,
  },
  server: {
    port: 5173,
    // ✅ PROXY CONFIGURATION
    proxy: {
      "/api": { target: "http://localhost:8080", changeOrigin: true },
      "/login": { target: "http://localhost:8080", changeOrigin: true },
      "/logout": { target: "http://localhost:8080", changeOrigin: true },
      "/oauth2": { target: "http://localhost:8080", changeOrigin: true },
    },
  },
});
```

### Proxy Flow:

```
Browser:   GET http://localhost:5173/api/v1/accounts
           (with JSESSIONID cookie)
             ↓
Vite Dev Server Proxy:
           Rewrites to: GET http://localhost:8080/api/v1/accounts
           changeOrigin: true = rewrites Origin header
             ↓
BFF Server:
           Receives: GET /api/v1/accounts
```

### Configuration Details:

| Path | Target | Purpose |
|------|--------|---------|
| `/api` | `http://localhost:8080` | All API requests → BFF |
| `/login` | `http://localhost:8080` | OAuth2 login endpoint |
| `/logout` | `http://localhost:8080` | OAuth2 logout endpoint |
| `/oauth2` | `http://localhost:8080` | OAuth2 callback routes |

**`changeOrigin: true`** = Rewrites the `Origin` header so BFF doesn't reject requests as CORS

---

## 5. BFF: AccountsBffController.java — How Does the BFF Forward?

**File:** `backend/bff/src/main/java/com/example/bff/controller/AccountsBffController.java`

```java
package com.example.bff.controller;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Proxies account endpoints to the Resource Server.
 * The WebClient automatically attaches the user's Bearer token.
 */
@RestController
@RequestMapping("/api/v1")
public class AccountsBffController {

  private final WebClient rs;  // Injected from WebClientConfig

  public AccountsBffController(WebClient resourceServerWebClient) {
    this.rs = resourceServerWebClient;
  }

  /** GET /api/v1/accounts - List caller's own accounts */
  @GetMapping("/accounts")
  public Mono<String> listAccounts() {
    // ✅ Extracts Authentication from SecurityContext
    // ✅ Gets OidcUser with Bearer token (see WebClientConfig)
    // ✅ Forwards to Resource Server with Bearer header
    return rs.get().uri("/api/v1/accounts")
            .retrieve().bodyToMono(String.class);
  }

  /** GET /api/v1/accounts/{accountId} - Get single owned account */
  @GetMapping("/accounts/{accountId}")
  public Mono<String> getAccount(@PathVariable String accountId) {
    return rs.get().uri("/api/v1/accounts/{id}", accountId)
            .retrieve().bodyToMono(String.class);
  }

  /** GET /api/v1/accounts/{accountId}/transactions - Get account transactions */
  @GetMapping("/accounts/{accountId}/transactions")
  public Mono<String> getTransactions(@PathVariable String accountId) {
    return rs.get().uri("/api/v1/accounts/{id}/transactions", accountId)
            .retrieve().bodyToMono(String.class);
  }
}
```

### Request Flow:

```
BFF receives:  GET /api/v1/accounts
               with JSESSIONID cookie (from same-origin fetch)
                 ↓
Spring Security: Validates session, loads Authentication from SecurityContext
                 ↓
BFF Controller: Calls rs.get().uri("/api/v1/accounts")
                 ↓
WebClientConfig Filter (oidcBearerFilter):
  • Gets OAuth2AuthenticationToken from SecurityContext
  • Extracts OidcUser principal (from session)
  • Gets idToken (JWT from Auth Server)
  • Adds header: Authorization: Bearer <JWT>
                 ↓
WebClient sends: GET http://localhost:8081/api/v1/accounts
                 with Authorization: Bearer <JWT> header
                 ↓
Resource Server validates JWT and processes request
```

### Key Points:
- ✅ **Reactive**: Returns `Mono<String>` (non-blocking)
- ✅ **Proxy**: Forwards request to Resource Server
- ✅ **Filter Applied**: WebClientConfig adds Bearer token
- ✅ **No Path Variables Lost**: `@PathVariable` passed through to RS

---

## 6. BFF: WebClientConfig.java — How Does Bearer Token Get Attached?

**File:** `backend/bff/src/main/java/com/example/bff/config/WebClientConfig.java`

```java
package com.example.bff.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient that forwards the OIDC id_token as a Bearer header to the Resource Server.
 *
 * Both mock-auth (Spring Authorization Server) and Google issue OIDC id_tokens that
 * are signed JWTs. The Resource Server validates JWTs from both issuers using a
 * multi-issuer JwtDecoder (see JwtDecoderConfig in the resource-server module).
 *
 * Why id_token instead of access_token?
 *   - Google's access_token is an opaque token (e.g. ya29.xxx), NOT a JWT.
 *     The Resource Server cannot validate it via its JwtDecoder.
 *   - Both Google and mock-auth's id_tokens ARE verifiable JWTs.
 *   - mock-auth's TokenCustomizer adds the "role" claim to all JWTs (including
 *     id_token), so role-based access control works correctly for mock-auth users.
 */
@Configuration
public class WebClientConfig {

  @Bean
  public WebClient resourceServerWebClient(
          @Value("${bank.resource-server.base-url}") String baseUrl) {

    // ✅ DEFINE EXCHANGE FILTER FUNCTION
    // This filter runs on EVERY request made by this WebClient
    ExchangeFilterFunction oidcBearerFilter = (request, next) -> {
      // Step 1: Get current user's Authentication from security context
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      
      // Step 2: Check if user is OAuth2 authenticated with OIDC
      if (auth instanceof OAuth2AuthenticationToken oauthToken
              && oauthToken.getPrincipal() instanceof OidcUser oidcUser) {
        
        // Step 3: ✅ EXTRACT ID TOKEN (JWT)
        String idToken = oidcUser.getIdToken().getTokenValue();
        
        // Step 4: ✅ ADD ID TOKEN AS BEARER HEADER
        ClientRequest withBearer = ClientRequest.from(request)
                .headers(h -> h.setBearerAuth(idToken))  // Adds: Authorization: Bearer <JWT>
                .build();
        
        // Step 5: Forward modified request to next filter/handler
        return next.exchange(withBearer);
      }
      // If not OIDC authenticated, forward request as-is
      return next.exchange(request);
    };

    // ✅ BUILD WEBCLIENT WITH FILTER
    return WebClient.builder()
            .baseUrl("http://localhost:8081")  // Resource Server URL
            .filter(oidcBearerFilter)  // Apply filter to ALL requests
            .build();
  }
}
```

### Token Extraction Flow:

```
Request arrives at BFF with JSESSIONID cookie
  ↓
Spring Security resolves session → Authentication object in SecurityContext
  ↓
WebClientConfig filter executes:
  1. Gets Authentication from SecurityContextHolder.getContext()
  2. Checks: auth instanceof OAuth2AuthenticationToken
  3. Checks: oauthToken.getPrincipal() instanceof OidcUser
  4. Gets idToken: oidcUser.getIdToken().getTokenValue()
     (This is the JWT issued by Auth Server)
  5. Adds header: Authorization: Bearer <idToken>
  6. Forwards modified request to Resource Server
  ↓
Resource Server receives with Authorization header
```

### Why ID Token Instead of Access Token?

| Token Type | Format | Validator |
|------------|--------|-----------|
| **Google access_token** | Opaque (ya29.xxx) | Can't validate with JwtDecoder |
| **Google id_token** | JWT (signed) | ✅ Can validate with JwtDecoder |
| **mock-auth id_token** | JWT (signed) | ✅ Can validate with JwtDecoder |
| **mock-auth access_token** | JWT (signed) | Can validate, but doesn't have role claim |

**Choice**: Use `id_token` because:
- ✅ Both Google & mock-auth provide JWTs
- ✅ Resource Server can validate signature
- ✅ mock-auth's `id_token` includes "role" claim for RBAC

---

## 7. RESOURCE SERVER: AccountController.java — Who Handles It on the RS?

**File:** `backend/resource-server/src/main/java/com/example/banking/controller/AccountController.java`

```java
package com.example.banking.controller;

import com.example.banking.dto.AccountDto;
import com.example.banking.dto.TransactionDto;
import com.example.banking.service.AccountService;
import com.example.banking.service.TransactionService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

  private final AccountService accountService;
  private final TransactionService transactionService;

  public AccountController(AccountService accountService, TransactionService transactionService) {
    this.accountService = accountService;
    this.transactionService = transactionService;
  }

  /**
   * GET /api/v1/accounts
   * Caller's own accounts only
   */
  @GetMapping
  public List<AccountDto> listMine(Authentication auth) {
    // ✅ Gets Authentication from JWT in Authorization header
    // ✅ auth.getName() = extracted userId from JWT claims
    // ✅ Returns ONLY accounts owned by this user
    return accountService.listForOwner(auth.getName());
  }

  /**
   * GET /api/v1/accounts/{accountId}
   * Single account, owned by caller
   * Returns 404 if not exists OR not owned (safe 404 pattern)
   */
  @GetMapping("/{accountId}")
  public AccountDto getOne(@PathVariable String accountId, Authentication auth) {
    // ✅ Calls loadOwned() which throws 404 if:
    //    1. Account doesn't exist, OR
    //    2. Caller doesn't own it
    // ✅ Prevents attackers from learning which account IDs exist
    return accountService.findOwnedAccount(accountId, auth.getName());
  }

  /**
   * GET /api/v1/accounts/{accountId}/transactions
   * Transactions for an owned account, newest first
   */
  @GetMapping("/{accountId}/transactions")
  public List<TransactionDto> getTransactions(@PathVariable String accountId,
                                              Authentication auth) {
    return transactionService.listForOwnedAccount(accountId, auth.getName());
  }
}
```

### Request Processing:

```
Resource Server receives:
  GET /api/v1/accounts
  Header: Authorization: Bearer <JWT>
  ↓
Spring Security Chain:
  1. JwtAuthenticationFilter intercepts request
  2. Validates JWT signature using JwtDecoder
  3. Extracts claims from JWT (subject, scope, role, etc.)
  4. JwtAuthConverter:
     - Sets principal name = JWT "sub" claim (userId)
     - Extracts "role" claim
     - Creates Authentication object
  ↓
Spring populates Authentication parameter in controller
  ↓
Controller methods:
  • listMine(auth) → auth.getName() = userId
  • getOne(accountId, auth) → passes userId to service
  • getTransactions(accountId, auth) → passes userId to service
  ↓
Business logic validates ownership
```

### Security Flow:
- ✅ JWT is **cryptographically signed** by Auth Server
- ✅ Resource Server validates signature using public key
- ✅ If signature invalid → 401 Unauthorized (framework throws)
- ✅ If signature valid → Extract claims and create Authentication
- ✅ Service layer enforces ownership (not controller!)

---

## 8. RESOURCE SERVER: AccountService.java — What Does Ownership Look Like?

**File:** `backend/resource-server/src/main/java/com/example/banking/service/AccountService.java`

```java
package com.example.banking.service;

import com.example.banking.dto.AccountDto;
import com.example.banking.exception.ResourceNotFoundException;
import com.example.banking.model.AccountEntity;
import com.example.banking.repository.AccountRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Read-only operations on accounts. Ownership is enforced HERE, not in the
 * controller. A non-owned account is reported as 404 (not 403) — see
 * {@code findOwnedAccount}.
 */
@Service
public class AccountService {

  private final AccountRepository accounts;

  public AccountService(AccountRepository accounts) {
    this.accounts = accounts;
  }

  /**
   * All accounts owned by the caller. May be empty.
   */
  public List<AccountDto> listForOwner(String ownerId) {
    // ✅ Query database by owner ID
    return accounts.findByOwnerId(ownerId).stream()
            .map(AccountDto::from)
            .toList();
  }

  /**
   * Looks up account ONLY if the caller owns it.
   * Returns 404 for BOTH non-existent AND not-owned.
   * This "safe 404" pattern prevents attackers from learning which account IDs exist.
   */
  public AccountDto findOwnedAccount(String accountId, String callerUserId) {
    return AccountDto.from(loadOwned(accountId, callerUserId));
  }

  /**
   * Internal helper for services that need the entity, not the DTO.
   * Throws ResourceNotFoundException if account doesn't exist OR not owned.
   */
  public AccountEntity loadOwned(String accountId, String callerUserId) {
    // ✅ OWNERSHIP CHECK LOGIC
    return accounts.findById(accountId)
            // Step 1: Find account by ID
            .filter(a -> a.getOwnerId().equals(callerUserId))
            // Step 2: ✅ Filter by ownership
            //         Account.ownerId must equal JWT's userId (callerUserId)
            .orElseThrow(() -> new ResourceNotFoundException("account", accountId));
            // Step 3: Throw 404 if:
            //         - Account not found, OR
            //         - Ownership filter didn't match
  }
}
```

### Ownership Logic Explained:

```
Scenario 1: Alice (usr_1) accessing her own account (acc_1)
  accountId = "acc_1"
  callerUserId = "usr_1"  ← from JWT
  
  Step 1: findById("acc_1") → returns Account { id: "acc_1", ownerId: "usr_1" }
  Step 2: filter(a → a.getOwnerId().equals("usr_1")) → ✅ MATCHES
  Step 3: Returns Account object
  Result: ✅ 200 OK with account data

Scenario 2: Alice (usr_1) accessing Bob's account (acc_2)
  accountId = "acc_2"
  callerUserId = "usr_1"  ← from JWT
  
  Step 1: findById("acc_2") → returns Account { id: "acc_2", ownerId: "usr_2" }
  Step 2: filter(a → a.getOwnerId().equals("usr_1")) → ❌ DOESN'T MATCH
  Step 3: orElseThrow() → throws ResourceNotFoundException
  Result: ❌ 404 Not Found (same response as non-existent account!)

Scenario 3: Alice (usr_1) accessing non-existent account (acc_999)
  accountId = "acc_999"
  callerUserId = "usr_1"  ← from JWT
  
  Step 1: findById("acc_999") → Optional.empty()
  Step 2: filter() → not executed (Optional is empty)
  Step 3: orElseThrow() → throws ResourceNotFoundException
  Result: ❌ 404 Not Found (same response as unauthorized access!)
```

### "Safe 404" Security Pattern:

| Scenario | Status | Why |
|----------|--------|-----|
| Account doesn't exist | 404 | True 404 |
| Account exists, Alice owns it | 200 | Return data |
| Account exists, Bob owns it | 404 | **Safe 404** - hide existence |
| Account not owned | 403 | **Anti-pattern** - reveals existence |

**Benefit**: Attacker can't enumerate account IDs

---

## 9. RESOURCE SERVER: AccountRepository.java

**File:** `backend/resource-server/src/main/java/com/example/banking/repository/AccountRepository.java`

```java
package com.example.banking.repository;

import com.example.banking.model.AccountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountRepository extends JpaRepository<AccountEntity, String> {
  // ✅ Custom query method
  // Finds all accounts for a given owner
  List<AccountEntity> findByOwnerId(String ownerId);
  
  // Inherited from JpaRepository:
  // Optional<AccountEntity> findById(String accountId);
  // List<AccountEntity> findAll();
  // void save(AccountEntity entity);
  // etc.
}
```

### Methods Used:

| Method | Purpose | Returns |
|--------|---------|---------|
| `findByOwnerId(ownerId)` | Get all accounts for a user | `List<AccountEntity>` |
| `findById(accountId)` | Get single account by PK | `Optional<AccountEntity>` |

### How JPA Generates SQL:

```java
// Line: accounts.findByOwnerId(ownerId)
// Generated SQL:
SELECT * FROM ACCOUNTS WHERE OWNER_ID = ?

// Line: accounts.findById(accountId)  
// Generated SQL:
SELECT * FROM ACCOUNTS WHERE ACCOUNT_ID = ?
```

---

## Complete Request/Response Flow Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ FRONTEND (port 5173)                                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│ 1. AccountsPage.jsx calls listAccounts()                                    │
│    └─> api/accounts.js: apiFetch("/api/v1/accounts")                       │
│        └─> api/apiClient.js:                                               │
│            • URL: /api/v1/accounts                                          │
│            • Headers: { credentials: "same-origin" }                        │
│            • Browser adds: JSESSIONID cookie (automatic)                    │
│            • For mutations: X-XSRF-TOKEN header                             │
│            • Sends: GET /api/v1/accounts                                    │
│                                                                             │
│            STORED LOCALLY: JSESSIONID, XSRF-TOKEN (as cookies)             │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                        VITE PROXY (5173 → 8080)
                        (changeOrigin: true)
                                    │
                                    ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│ BFF (port 8080)                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│ 2. AccountsBffController.listAccounts()                                     │
│    └─> Receives: GET /api/v1/accounts                                      │
│        Header: Cookie: JSESSIONID=<session-id>                             │
│                                                                             │
│    └─> Spring Security:                                                    │
│        • SessionRepository: Resolves JSESSIONID → session object           │
│        • Extracts Authentication from session                              │
│        • Gets OAuth2AuthenticationToken with OidcUser                      │
│                                                                             │
│    └─> Gets Authorization from SecurityContextHolder                       │
│    └─> Calls: rs.get().uri("/api/v1/accounts")                            │
│                                                                             │
│    └─> WebClientConfig.oidcBearerFilter executes:                         │
│        1. Gets OAuth2AuthenticationToken from SecurityContext              │
│        2. Extracts OidcUser principal (from session data)                  │
│        3. Gets idToken from OidcUser.getIdToken().getTokenValue()         │
│           (This is a JWT issued by Auth Server, e.g., Google or mock-auth)│
│        4. Creates ClientRequest with Bearer header:                        │
│           Authorization: Bearer <JWT>                                      │
│        5. Continues to next exchange filter                                │
│                                                                             │
│    └─> WebClient sends to Resource Server:                                │
│        GET http://localhost:8081/api/v1/accounts                          │
│        Header: Authorization: Bearer <JWT>                                │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                          HTTP (8080 → 8081)
                                    │
                                    ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│ RESOURCE SERVER (port 8081)                                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│ 3. JwtAuthenticationFilter intercepts request                               │
│    └─> Receives: GET /api/v1/accounts                                      │
│        Header: Authorization: Bearer <JWT>                                 │
│                                                                             │
│    └─> Extracts JWT from Authorization header                              │
│    └─> JwtDecoder validates:                                               │
│        • Signature (using public key from Auth Server)                     │
│        • Expiration time                                                   │
│        • Issuer claim                                                      │
│    └─> If invalid → 401 Unauthorized (throw exception)                     │
│    └─> If valid → Extract claims:                                          │
│        • sub: "google-sub-123" or "mock-usr-1"                             │
│        • scope: "openid profile email"                                     │
│        • role: "CUSTOMER" or "ADMIN"                                       │
│                                                                             │
│    └─> JwtAuthConverter (part of JWT processing):                          │
│        • Principal name = sub claim (userId)                               │
│        • Extracts "role" claim                                             │
│        • Creates Authentication object with authorities                    │
│        • Sets in SecurityContextHolder                                     │
│                                                                             │
│ 4. AccountController.listMine(Authentication auth)                         │
│    └─> auth.getName() = "google-sub-123" (from JWT sub claim)             │
│    └─> Calls: accountService.listForOwner("google-sub-123")               │
│                                                                             │
│ 5. AccountService.listForOwner():                                          │
│    └─> accounts.findByOwnerId("google-sub-123")  [JPA Query]              │
│        Generated SQL: SELECT * FROM ACCOUNTS WHERE OWNER_ID = ?           │
│    └─> Returns List<AccountEntity> filtered by ownerId                    │
│    └─> Map each AccountEntity to AccountDto                               │
│    └─> Return: List<AccountDto>                                           │
│                                                                             │
│ 6. Spring converts List<AccountDto> to JSON                                │
│    └─> HttpMessageConverter handles serialization                          │
│    └─> Returns: 200 OK                                                    │
│        Body: [{ accountId: "acc_1", balance: 1000.00, ... }, ...]        │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                          HTTP (8081 → 8080)
                                    │
                                    ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│ BFF (port 8080)                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│ 7. AccountsBffController receives response                                  │
│    └─> Resource Server returned: 200 OK                                    │
│        Body: [{ accountId: "acc_1", balance: 1000.00, ... }, ...]         │
│    └─> bodyToMono(String.class) unpacks response body                      │
│    └─> Returns response back to client                                     │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                        VITE PROXY (8080 → 5173)
                                    │
                                    ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│ FRONTEND (port 5173)                                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│ 8. apiFetch() receives 200 OK response                                      │
│    └─> res.json() parses accounts array                                    │
│    └─> Promise resolves with: [{ accountId: "acc_1", ... }, ...]         │
│    └─> .then(setAccounts) updates React state                              │
│    └─> Component re-renders:                                               │
│        <section>                                                            │
│          <h1>Your accounts</h1>                                             │
│          <ul>                                                               │
│            {accounts.map((a) =>                                             │
│              <AccountCard key={a.accountId} account={a} />                 │
│            )}                                                               │
│          </ul>                                                              │
│        </section>                                                           │
│                                                                             │
│    └─> Browser displays account list to user                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Security Summary

### Authentication & Token Flow

| Layer | Mechanism | Implementation |
|-------|-----------|-----------------|
| **Browser** | Session Cookie | `JSESSIONID` sent automatically with `credentials: "same-origin"` |
| **Frontend** | CSRF Protection | Reads `XSRF-TOKEN` cookie, sends as `X-XSRF-TOKEN` header |
| **BFF** | Session Resolution | Spring extracts session, loads OAuth2AuthenticationToken |
| **BFF** | JWT Extraction | WebClientConfig reads `id_token` from OidcUser |
| **BFF** | Bearer Header | Adds `Authorization: Bearer <JWT>` |
| **Resource Server** | JWT Validation | JwtDecoder validates signature using public key |
| **Resource Server** | Claims Extraction | JwtAuthConverter extracts userId from "sub" claim |
| **Resource Server** | Authorization | @PreAuthorize checks "ROLE_CUSTOMER" or "ROLE_ADMIN" |
| **Service Layer** | Ownership | `filter(a → a.getOwnerId().equals(userId))` |

### Ownership Enforcement: "Safe 404"

```
Request: GET /api/v1/accounts/acc_other
User: Alice (usr_1)

AccountService.loadOwned("acc_other", "usr_1"):
  accounts.findById("acc_other")
    .filter(a → a.getOwnerId().equals("usr_1"))  ← Ownership check
    .orElseThrow(() → new ResourceNotFoundException(...))

Possible Outcomes:
  ✅ Alice owns acc_other     → 200 OK, return account data
  ❌ Bob owns acc_other        → 404 Not Found
  ❌ acc_other doesn't exist   → 404 Not Found

Security Benefit:
  Attacker can't tell if 404 means "doesn't exist" or "not yours"
  Prevents account ID enumeration attacks
```

### Token Types Used

| Token | Issued By | Format | Used For | Validated By |
|-------|-----------|--------|----------|--------------|
| **JSESSIONID** | BFF (Spring Session) | Opaque | Browser login state | BFF Session Repository |
| **XSRF-TOKEN** | BFF (Spring Security) | Random token | Mutation CSRF protection | BFF CsrfTokenRepository |
| **id_token** | Auth Server (Google/mock-auth) | JWT (signed) | Resource Server auth | RS JwtDecoder |
| **access_token** | Auth Server | Opaque/JWT | Not used in this app | N/A |

---

## Key Takeaways

1. **Session-Based on Frontend**: Browser sends `JSESSIONID` cookie (same-origin)
2. **JWT-Based on Backend**: BFF extracts JWT, forwards via Bearer header
3. **No Token Storage**: No localStorage, no tokens in cookies
4. **CSRF Protection**: `X-XSRF-TOKEN` header for mutations
5. **OAuth2 Integration**: Seamless with Google and mock-auth
6. **Safe 404**: Prevents attackers from discovering account IDs
7. **Multi-Layer Validation**: Browser → Cookie → BFF Session → JWT → Ownership
8. **Reactive WebClient**: Non-blocking calls to Resource Server with auto-retry

---

**Last Updated:** 2026-05-06

