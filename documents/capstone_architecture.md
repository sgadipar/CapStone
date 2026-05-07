# Capstone Architecture: Secure Digital Banking Platform

## Executive Summary

The Secure Digital Banking Platform follows a layered, OAuth2-secured microservices architecture designed for security and separation of concerns. The **React/Vite Frontend** (SPAs) acts as the user interface and securely obtains OAuth2 tokens via the **mock-auth service** (Spring Authorization Server), which generates and signs JWT tokens. These tokens are then forwarded through the **BFF (Backend-for-Frontend)** on port 8080, which acts as a secure API gateway that extracts the JWT from sessions, validates it, and re-attaches it as a Bearer token when proxying requests to the **Resource Server** (port 8081). The **Resource Server** implements multi-layered security by validating JWT signatures via a multi-issuer JwtDecoder supporting both mock-auth and Google OIDC providers, enforcing role-based access control (ROLE_ADMIN vs ROLE_CUSTOMER) at the controller level with @PreAuthorize, and implementing resource ownership checks at the service layer to prevent unauthorized account access. All transaction operations are persisted to **Oracle 21c XE** via Flyway migrations and published as Kafka events for asynchronous processing. External payment processor calls are abstracted through **WireMock stubs** in development for safe testing without hitting real payment systems. This architecture ensures end-to-end security through token validation, ownership enforcement, role-based authorization, and separation between the frontend-facing BFF and the protected resource server.

---

## Architecture Components

### 1. Frontend (React/Vite SPA)
- **Port:** 5173
- **Technology:** React, Vite, React Router
- **Responsibilities:**
  - User interface for account management and transactions
  - OAuth2 OIDC login flow integration
  - Client-side routing with SPA historyApiFallback
  - API client with session-based CSRF protection
  - Error handling and loading states

**Security Features:**
- Session-based CSRF tokens on all state-changing requests
- Secure session cookies (HttpOnly, SameSite)
- Client-side OAuth2 code authorization flow
- Proxy-based API routing through BFF

### 2. mock-auth (OAuth2 Authorization Server)
- **Port:** 9000
- **Technology:** Spring Authorization Server
- **Responsibilities:**
  - OAuth2 authorization endpoint for account/password login
  - OIDC identity provider services
  - JWT token generation and signing
  - Custom token claims (including "role" claim)
  - Multi-issuer support (alongside Google OIDC)

**Security Features:**
- Signed JWT tokens with RS256 algorithm
- Restricted audience validation ("bank-client-bff")
- Token expiry enforcement
- Custom claim support for role-based access

### 3. BFF (Backend-for-Frontend)
- **Port:** 8080
- **Technology:** Spring Boot, WebClient
- **Responsibilities:**
  - OAuth2 client application (receives auth code, exchanges for token)
  - Session management and CSRF middleware
  - JWT extraction from session storage
  - WebClient proxy to Resource Server with Bearer token re-attachment
  - API error mapping and response normalization
  - Cache layer for user/account data (optional)

**Security Features:**
- OAuth2 confidential client with client credentials
- Session-based token storage (not in localStorage)
- Bearer token propagation to Resource Server
- Multi-issuer JwtDecoder configuration
- CORS configuration for frontend domain
- Security context propagation through SecurityContext

### 4. Resource Server (Core Banking API)
- **Port:** 8081
- **Technology:** Spring Boot, JPA, Spring Security
- **Responsibilities:**
  - Account management endpoints (GET /api/v1/accounts)
  - Transaction processing (POST /api/v1/transactions)
  - Deposit, withdrawal, and transfer operations
  - Admin operations (GET /api/v1/admin/users)
  - Kafka event publishing for transactions
  - Payment processor integration (external transfers)

**Security Features:**
- JWT validation via multi-issuer JwtDecoder
- Role-based access control (@PreAuthorize for ROLE_ADMIN)
- Resource ownership enforcement at service layer
- Safe 404 pattern (non-owned = 404, not 403)
- Request scope authentication extraction

**Endpoints:**
- `GET /api/v1/accounts` — List all owned accounts (CUSTOMER role)
- `GET /api/v1/accounts/{id}` — Get single account details (CUSTOMER role)
- `GET /api/v1/accounts/{id}/transactions` — Get transaction history (CUSTOMER role)
- `POST /api/v1/transactions` — Create deposit/withdrawal/transfer (CUSTOMER role)
- `GET /api/v1/admin/users` — List all users (ADMIN role only)
- `GET /health` — Public health endpoint

### 5. WireMock (Payment Processor Stubs)
- **Port:** 8089
- **Technology:** WireMock HTTP stubbing
- **Responsibilities:**
  - Mock external payment processor
  - Stub payment endpoint responses
  - Support both success and failure scenarios
  - Prevent real external API calls in development/testing

**Stub Mappings:**
- `POST /v1/payments/submit` — Payment processing endpoint
- Success scenarios (HTTP 200) for internal transfers
- Failure scenarios (HTTP 503, 504) for testing error handling

### 6. Oracle Database
- **Port:** 1521
- **Technology:** Oracle 21c Express Edition
- **Responsibilities:**
  - Persistent storage for accounts, users, transactions
  - Foreign key and referential integrity
  - Transaction isolation
  - Audit logging

**Schema:**
- `USERS` — Application users with email, role
- `ACCOUNTS` — Bank accounts with owner, balance, currency
- `TRANSACTIONS` — Transaction ledger with type, status, amount
- Flyway versioned migrations for schema management

### 7. Apache Kafka
- **Port:** 9092 (broker), 2181 (Zookeeper)
- **Technology:** Apache Kafka, Zookeeper
- **Responsibilities:**
  - Asynchronous transaction event processing
  - Event streaming for completed transactions
  - Decoupling of transaction completion from analytics/audit

**Topics:**
- `transactions.completed` — Published when a deposit, withdrawal, or transfer completes

---

## Security Model

### Authentication Flow
```
1. User navigates to Frontend (React/Vite)
   ↓
2. Frontend redirects to mock-auth /oauth2/authorize
   ↓
3. mock-auth generates authorization code
   ↓
4. Authorization code returned to Frontend → BFF
   ↓
5. BFF exchanges authorization code for JWT (backend call)
   ↓
6. JWT stored in secure session (HttpOnly, SameSite)
   ↓
7. Session cookie returned to Frontend
   ↓
8. Frontend can now makerequest to /api/... (proxied through BFF)
```

### Authorization Layers

**Layer 1: Controller-Level (@PreAuthorize)**
- Checks user role (ROLE_ADMIN vs ROLE_CUSTOMER)
- Example: Only ROLE_ADMIN can call GET /api/v1/admin/users
- Fast-fail mechanism for coarse-grained access

**Layer 2: Service-Level (Resource Ownership)**
- Checks if user owns the requested resource
- Example: ROLE_CUSTOMER cannot access other users' accounts
- Safe 404 pattern: returns 404 for both missing AND unauthorized resources
- Prevents information disclosure about account ID existence

### JWT Validation
- Multi-issuer support: accepts tokens from mock-auth OR Google OIDC
- Issuer validation: verifies "iss" claim matches trusted issuer
- Signature verification: validates RS256 signature
- Audience validation: ensures token has correct audience ("bank-client-bff")
- Expiry validation: rejects expired tokens
- Claims extraction: role, userID, email from JWT

### CSRF Protection
- Frontend includes CSRF token in session headers
- BFF/Resource Server validates CSRF token on state-changing requests
- Token rotation on login
- HttpOnly cookies prevent JavaScript access to session tokens

---

## Data Flow Example: POST /api/v1/transactions (Create Deposit)

```
1. Frontend (React) → User clicks "Deposit" button
   ├─ Input: accountId, amount, description
   └─ Action: calls POST /api/v1/transactions via apiFetch()

2. apiFetch() → Builds HTTP request
   ├─ URL: /api/v1/transactions (relative to frontend origin)
   ├─ Headers: { "Content-Type": "application/json", "X-CSRF-Token": "..." }
   ├─ Body: { "accountId": "acc_1", "type": "DEPOSIT", "amount": 50.00, ... }
   └─ Cookies: Includes session cookie (Frontend's secure session)

3. Vite Dev Server → Proxies request
   ├─ Proxy rule: /api → http://localhost:8080
   └─ Forwards request to BFF with session cookie

4. BFF (Spring Boot) → Receives request on POST /api/v1/transactions
   ├─ Extracts JWT from session storage
   ├─ Validates CSRF token
   ├─ Creates WebClient request with Bearer token: { "Authorization": "Bearer <JWT>" }
   └─ Proxies to Resource Server: POST http://localhost:8081/api/v1/transactions

5. Resource Server → SecurityConfig intercepts request
   ├─ Extracts Bearer token from Authorization header
   ├─ JwtDecoder validates JWT signature, issuer, expiry
   ├─ Extracts user context (userID, role, email from token)
   └─ Attaches to SecurityContext

6. TransactionController → @PostMapping("/api/v1/transactions")
   ├─ Receives authenticated request
   ├─ @PreAuthorize("hasRole('CUSTOMER')") — checks role
   ├─ Calls transactionService.submit(request, userId)
   └─ Returns 201 Created with TransactionDto

7. TransactionService → Business logic
   ├─ Loads account via accountService.loadOwned(accountId, userId)
   │  └─ If account not owned by userId → throws ResourceNotFoundException
   ├─ Validates business rules (e.g., no counterparty for DEPOSIT)
   ├─ Calls applyDeposit(account, request)
   │  ├─ Validates amount > 0
   │  ├─ Updates account.balance += amount
   │  ├─ Saves AccountEntity to Oracle DB
   │  └─ Creates TransactionEntity row
   ├─ Publishes Kafka event: TransactionEvent with status=COMPLETED
   └─ Returns List<TransactionDto>

8. Resource Server → HTTP Response (201 Created)
   ├─ Body: [ { "transactionId": "txn_1", "status": "COMPLETED", ... } ]
   └─ Headers: { "Content-Type": "application/json" }

9. BFF → Response forwarded to Frontend
   ├─ Status code maintained (201)
   └─ Body returned as-is

10. Frontend (React) → Handles response
    ├─ Updates component state with transaction
    ├─ Displays success message
    └─ Rerenders account list (if applicable)

11. Kafka → TransactionEventPublisher publishes event
    ├─ Topic: transactions.completed
    ├─ Payload: { "transactionId": "txn_1", "accountId": "acc_1", ... }
    └─ Subscribers can now update analytics, audit logs, etc.
```

---

## Deployment Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                          Docker Network                          │
└──────────────────────────────────────────────────────────────────┘
│
├─ Frontend (Vite Dev)
│  └─ Port 5173 → Proxies /api to BFF
│
├─ BFF (Spring Boot)
│  └─ Port 8080 → OAuth2 Client, WebClient forwarding
│
├─ mock-auth (Spring Authorization Server)
│  └─ Port 9000 → JWT generation, OIDC discovery
│
├─ Resource Server (Spring Boot)
│  └─ Port 8081 → REST API with security
│
├─ WireMock (Payment Processor)
│  └─ Port 8089 → Stubbed payment endpoints
│
├─ Oracle Database
│  └─ Port 1521 → Accounts, transactions, users
│
├─ Kafka Broker
│  └─ Port 9092 → Transaction event streaming
│
└─ Zookeeper
   └─ Port 2181 → Kafka coordination
```

---

## Security Best Practices Implemented

| Practice | Implementation | Benefit |
|----------|---|---|
| **JWT Validation** | Multi-issuer JwtDecoder with signature verification | Prevents token forgery, ensures authenticity |
| **Role-Based Access** | @PreAuthorize("hasRole(...)") on controllers | Fast-fail for unauthorized roles |
| **Ownership Checks** | Service-layer validation in loadOwned() | Prevents users from accessing others' accounts |
| **Safe 404 Pattern** | Returns 404 for both missing and unauthorized | Prevents information disclosure |
| **Bearer Token Propagation** | BFF extracts JWT and re-attaches as Bearer | Stateless, scalable architecture |
| **CSRF Protection** | Session headers with X-CSRF-Token | Prevents cross-site form submission attacks |
| **HttpOnly Cookies** | Session tokens not accessible via JavaScript | Prevents XSS token theft |
| **SameSite Cookies** | Strict SameSite attribute on session cookies | Prevents CSRF attacks |
| **External API Stubbing** | WireMock for payment processor | Safe development/testing without real API calls |
| **Database Encryption** | Oracle DBMS encryption options | Protects data at rest |

---

## Performance Considerations

- **Caching:** BFF can cache user/account data to reduce Resource Server calls
- **Kafka Async:** Transaction events processed asynchronously, not blocking user
- **Connection Pooling:** HikariCP for database connections
- **JWT Expiry:** Token refresh tokens for long-lived sessions without re-auth
- **Load Balancing:** Stateless Resource Server allows horizontal scaling

---

## Testing Strategy

- **Integration Tests:** @SpringBootTest with embedded Kafka and mocked services
- **Security Tests:** JWT validation, role-based access, ownership enforcement
- **Contract Tests:** Frontend ↔ BFF, BFF ↔ Resource Server
- **E2E Tests:** Frontend login → account list → transaction submission
- **Stub Testing:** WireMock success/failure scenarios for payment processing

---

## Deployment Checklist

- [ ] Mock-auth service running on port 9000, OIDC discovery endpoint accessible
- [ ] BFF configured with OAuth2 client credentials
- [ ] Resource Server JWT_DECODER_URI configured to mock-auth
- [ ] Oracle database schema migrated via Flyway
- [ ] Kafka queues created and topics initialized
- [ ] WireMock stub mappings loaded
- [ ] Frontend environment variables pointing to BFF (port 8080)
- [ ] CORS policy configured on BFF for frontend origin
- [ ] HTTPS/TLS enabled in production
- [ ] Environment variables secured (client secrets, DB passwords)


