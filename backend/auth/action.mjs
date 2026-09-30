export function readAction(href) {
  const url = new URL(href);
  const code = url.searchParams.get('oobCode');
  if (!code || !/^[A-Za-z0-9_-]{8,2048}$/.test(code)) throw new Error('missing-code');
  const mode = url.searchParams.get('mode');
  if (mode && !['verifyEmail', 'resetPassword', 'recoverEmail'].includes(mode)) throw new Error('unsupported-mode');
  if (url.searchParams.getAll('oobCode').length !== 1 || url.searchParams.getAll('mode').length > 1) throw new Error('invalid-link');
  return { code, mode };
}
export function readPastedAction(value) {
  // Email clients can copy HTML entities instead of literal query separators.
  // Repair only that encoding; never follow a third-party email tracking URL.
  let url;
  try { url = new URL(value.trim().replace(/&(?:amp|#0*38|#x0*26);/gi, '&')); }
  catch { throw new Error('invalid-link'); }
  if (url.protocol !== 'https:' || url.username || url.password || !['visidock-thotapalli.firebaseapp.com','visidock-thotapalli.web.app'].includes(url.host)) throw new Error('invalid-link');
  if (!['/__/auth/action','/auth','/auth/'].includes(url.pathname)) throw new Error('invalid-link');
  return readAction(url.href);
}
export async function inspectAction(action, authApi) {
  const info = await authApi.checkActionCode(action.code);
  const mode = { VERIFY_EMAIL:'verifyEmail', PASSWORD_RESET:'resetPassword', RECOVER_EMAIL:'recoverEmail' }[info.operation];
  if (!mode || (action.mode && action.mode !== mode)) throw new Error('unsupported-mode');
  return { ...action, mode };
}
export function actionError(error) {
  const code = error?.code || error?.message;
  if (['auth/expired-action-code','auth/invalid-action-code'].includes(code)) return 'This link has expired or has already been used. Open VisiDock and request a fresh email.';
  if (code === 'missing-code') return 'This link is incomplete. Open the full link in your latest VisiDock email.';
  if (['unsupported-mode','invalid-link'].includes(code)) return 'This link is not a supported VisiDock account action. Request a fresh email from the app.';
  if (code === 'auth/weak-password' || code === 'auth/password-does-not-meet-requirements') return 'Choose a stronger password with at least 8 characters.';
  if (code === 'auth/network-request-failed') return 'Could not connect. Check your internet connection and try again.';
  return 'We could not complete this request. Try again, or request a fresh link from VisiDock.';
}
