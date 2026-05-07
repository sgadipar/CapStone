# SAST Findings

## Finding 1: Potential Deserialization Vulnerability in CookieOAuth2AuthorizationRequestRepository.java

**File:** `backend/bff/src/main/java/com/example/bff/config/CookieOAuth2AuthorizationRequestRepository.java`

**Severity:** Medium

**Description:**
The `CookieOAuth2AuthorizationRequestRepository` class uses Java's `SerializationUtils.serialize()` and `SerializationUtils.deserialize()` methods to store and retrieve OAuth2 authorization requests in HTTP cookies. While the code includes a try-catch block in the `deserialize` method to handle exceptions, the use of Java serialization can be risky as it may allow deserialization of malicious objects if the serialized data is tampered with.

**Potential Impact:**
- Remote code execution if an attacker can inject malicious serialized data into the cookie.
- Although the cookie is HttpOnly and has a short TTL (3 minutes), any successful exploitation could compromise the application.

**Recommendation:**
Consider using a safer serialization method, such as JSON serialization with a library like Jackson, or store only the necessary data (e.g., state and code verifier) in a structured format rather than serializing the entire OAuth2AuthorizationRequest object.

**Code Location:**
- Serialization: Line 84-85
- Deserialization: Line 89-94

**Status:** Open


## Finding 2: Commented-Out Code Block in SecurityConfig.java

**File:** `backend/bff/src/main/java/com/example/bff/config/SecurityConfig.java`

**Severity:** Info

**Description:**
SonarQube has flagged a block of commented-out lines of code that should be removed. Commented-out code can make the codebase harder to read and maintain, and may indicate unfinished work or obsolete logic.

**Potential Impact:**
- Reduces code readability and maintainability.
- May confuse developers or lead to accidental re-enabling of outdated code.

**Recommendation:**
Remove the commented-out code block to clean up the codebase. If the code was commented out for a reason (e.g., future use), consider moving it to a separate file or adding a proper comment explaining why it's preserved.

**Code Location:**
- Specific lines not provided; review the file for commented-out blocks.

**Status:** Open
