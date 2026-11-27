# recovery

Trusted-person recovery. Depends on `domain` ports only; the session is touched only through
`SessionController`, so the state machine stays the single authority over a session.

- `RecoveryService` (implements `RecoveryController`): setup, change and removal of the trusted
  person (never while a session runs), the 24-hour removal wait, opening/cancelling/finishing
  recovery, wrong-PIN counting and lockout on two clocks, and the audit entries. One mutex.
- `Pbkdf2PinHasher`: salted PBKDF2-HMAC-SHA256 for the PIN. Never stores or logs a PIN.

Rules worth keeping: the session is ended first and the PIN used up second (never trap anyone);
recovery is opened only if a usable PIN exists; an audit write that fails never blocks recovery;
emergency access (calling, information) does not pass through this package at all.
