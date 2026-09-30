# Firebase support draft — not submitted

Project: `visidock-thotapalli`

Subject: Authorized hosted email-action callback rejected with EMAIL_TEMPLATE_UPDATE_NOT_ALLOWED

The project uses Firebase Authentication / Identity Platform (`subtype: IDENTITY_PLATFORM`) with the default email provider. Email/password sign-in is enabled. Authorized domains include `visidock-thotapalli.firebaseapp.com` and `visidock-thotapalli.web.app`.

The current callback is `https://visidock-thotapalli.firebaseapp.com/__/auth/action`. We deployed a custom handler at `https://visidock-thotapalli.web.app/auth`; HTML and assets respond HTTP 200 and handle verification, password reset, and email recovery. Synthetic verification/reset action codes work using Firebase's SDK. No custom SMTP, domain, provider, or security-policy change is requested.

An authenticated request to the documented project configuration API is rejected:

```
PATCH https://identitytoolkit.googleapis.com/admin/v2/projects/visidock-thotapalli/config?updateMask=notification.sendEmail.callbackUri
Content-Type: application/json

{"notification":{"sendEmail":{"callbackUri":"https://visidock-thotapalli.web.app/auth"}}}
```

Response: HTTP 400, `EMAIL_TEMPLATE_UPDATE_NOT_ALLOWED`. The callback remains unchanged.

Please identify which project restriction prevents the supported custom email handler setting and enable or explain the supported path for setting this callback. We do not want to change billing, lower authentication protections, or replace the default sender merely to change the action page.

Separately, a user reported “selected page mode is invalid” on an earlier received link. Fresh generated links contain all required parameters and work. We inspected the served default handler and confirmed that message is its missing/unsupported-parameter branch. We have not inspected the user's original secret action code and do not include any action codes, passwords, account details, API keys or tokens in this report.

Relevant documented workflow: https://firebase.google.com/docs/auth/custom-email-handler#link_to_your_custom_handler_in_your_email_templates

Submit through https://firebase.google.com/support only with authorization to contact support. No support message has been sent by the development task.
