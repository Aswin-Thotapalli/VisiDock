# VisiDock account actions

Source: `main.mjs`, pure parsing/dispatch `action.mjs`, and `public/` hosting assets. Copy `public-config.example.json` to `public-config.json` and supply your Firebase configuration locally. The real configuration and generated `public/app.js` are ignored by Git. Do not commit either file or publish Android bundles containing client keys as GitHub attachments. No password, ID token or email action code belongs in this directory.

From the repository root:

```
npm ci --prefix backend/auth
npm test --prefix backend/auth
node backend/r2/node_modules/esbuild/bin/esbuild backend/auth/main.mjs --bundle --minify --format=esm --outfile=backend/auth/public/app.js
node node_modules/firebase-tools/lib/bin/firebase.js deploy --project visidock-thotapalli --only hosting
```

`verify-live.cjs` is an explicit synthetic integration probe, not a unit test. It uses the local authenticated Firebase CLI session to generate links without sending email, then tests verification and password reset and cleans up its temporary account. Run it only when live synthetic verification is intended.

The hosted handler is not yet configured as Firebase's callback: the provider rejected that configuration with `EMAIL_TEMPLATE_UPDATE_NOT_ALLOWED`. See `docs/Auth-verification.md` for evidence and limitations.

The `/auth` page can nevertheless be opened directly for recovery: users can paste a full verification/reset/recovery link from their email. It accepts only this project's Firebase domains, repairs copied `&amp;` separators, and checks the action code with Firebase before offering any action. It does not send a new email, follow pasted URLs, or put pasted action codes in browser history. Android can open this URL from its Verification help action.
