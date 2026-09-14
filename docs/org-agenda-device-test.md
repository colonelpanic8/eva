# Deferred org-agenda device test

Status: **deferred, not failed**. Ivan is not deploying the server changes now.
The Pixel org-agenda test awaits an org-agenda-api server with:

- GET /get-all-todos accepting q and limit and returning total before limiting.
- POST /complete honoring fixed strict: true, resolving only the exact ID or
  file/pos/title, and returning HTTP 409 without mutation on a mismatch.

Nothing in this extension work is device-verified: the declarative packages,
installed-service adapter, and generalized AppFunctions path have no physical
phone verification result. JVM results do not establish Android interoperability.
The org-agenda document remains an HTTP example/test fixture and is not bundled
in the APK. Messages remains the bundled no-app-changes intent example.

Client-side result filtering can remove the server-side search dependency for
other package definitions, but it cannot make an old completion endpoint honor
strict lookup. Do not infer strict behavior from a successful response.

Continue implementation in order: result filtering; bundled intent migration and
share-to-app; repository import; generalized AppFunctions. Device testing is not
a prerequisite for continuing that queue. Resume this specific test only after
its server prerequisites are available.
