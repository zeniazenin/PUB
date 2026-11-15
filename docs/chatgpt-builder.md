# ChatGPT builder milestone — 0.2

## Reference and protocol

The user requested ChatGPT account login instead of an API key and identified their installed Roundlight app. `com.roundlight.app` was copied from the connected phone as an APK only; its private account data was not read. The REA plugin lacked configured ADB/JADX providers. Local SDK `dexdump` of `classes4.dex` showed Roundlight's `ChatGptAuth` constructing a loopback listener, random state/nonce/verifier, S256 challenge, `dynamic_agent_client`, stable host identifier, `agent_name_hint=Roundlight`, and direct ChatGPT plan scopes. Its package also contains the public models/Responses endpoint strings. These are static code observations, not a claim to have observed a Roundlight login at runtime.

PUB independently implements the same documented public-client pattern under `agent_name_hint=PUB`:

- Authorization: `https://auth.openai.com/api/accounts/authorize`.
- Resource: `https://api.openai.com/v1`.
- First registration uses `dynamic_agent_client`; code exchange uses the issued `oaiapp_…` client ID. Returning sign-in retains the issued ID and checks the verified subject.
- Callback binds **only** `127.0.0.1` on an ephemeral port at `/auth/callback`, started before opening the external system browser. URI is identical in authorization and token exchange. Requests have bounded size/time; duplicate query parameters and mismatched state are rejected. Listener ends on completion, cancellation or timeout.
- Scope: `openid profile email offline_access resource.invoke chatgpt.tokens.use.direct`.
- Token endpoint: `https://auth.openai.com/api/accounts/oauth/token`, form-encoded code + PKCE verifier, no client secret or API key.
- ID-token signature is verified with Nimbus JOSE against OpenAI's fixed JWKS endpoint; only RS256/ES256 are accepted. Issuer, audience, subject, expiry, issued-at, not-before, nonce and multiple-audience authorized party are checked.
- Granted scopes must include both direct plan use and resource invocation. The callback page asks the user to return to PUB before exchange, accounting for Android foreground networking restrictions.
- Refresh is serialized; rotating credentials and expiry are saved atomically. The original validated account subject is retained. HTTP errors are mapped to bounded user-facing messages; raw server/token bodies are not displayed or logged.
- Model catalog comes from `/v1/models`; only `visibility=list` entries appear, in server order. Inference calls `/v1/responses` with `store=false`, `stream=true`, an array of user messages in `input`, and the account's selected model. Only a successfully completed output stream can produce a draft. Cancellation is explicit; no silent inference retries.
- Logout discovers the revocation endpoint from OpenAI's OIDC configuration, restricts it to the HTTPS auth host, attempts refresh-token revocation, then clears local credentials. Failed remote revocation is disclosed.

Official references checked during implementation:

- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions
- https://developers.openai.com/siwc/website

## Package and execution flow

Model output contains exactly `name`, `description`, `capabilities`, and `source` (0.5.0 added the optional `assets` and `icon`; a generation response may also carry a `tests` array, which is removed and validated separately before the package is parsed; see docs/behavior-checks.md). Metadata, source size, JSON depth, capability allowlist and field set validate before preview. No model-supplied ID, entrypoint, path, URL or executable binary is accepted.

The host selects a random preview identity and launches the existing isolated runtime. Code defines `dispatch(jsonText)` returning a full native UI packet plus bounded effects. Text input events extend the original tuner contract. Generic `storage.save` stores an object under the current project only; old `settings.save` is restricted to the bundled tuner.

Save requires a successfully rendered packet without a runtime failure. It writes an atomic project file under a fresh UUID, copies preview state to that project, then opens the saved project. Permission decisions never transfer from a preview to the new saved identity. Switching stops capture and invalidates the previous runtime callbacks. Deletion removes the package, state and grant.

## Validation

JVM tests cover: PKCE RFC test vector; state/client binding; signed ID tokens and rejected issuer/audience/nonce/expiry/signature variants; bounded completed/incomplete/refused response streams; package path/capability rejection; generated input/storage behavior; original sandbox, pitch, tuner and policy tests.

Device tests cover preview → interaction → save → reopen → delete, separate project grants, denied INTERNET permission for the isolated UID even though the host has it, and encrypted-at-rest session roundtrip using a separate dummy test file. The original microphone lifecycle and Binder isolation tests remain.

Live ChatGPT sign-in, account-specific model loading, Tip Calculator generation, isolated preview, local saving and reopening after process restart were observed on the phone. See verification.md for exact arithmetic checks and remaining limits.

Additional protocol reference: https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations
