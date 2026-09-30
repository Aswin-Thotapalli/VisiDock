import { initializeApp } from 'firebase/app';
import { getAuth, checkActionCode, applyActionCode, confirmPasswordReset } from 'firebase/auth';
import { readAction, readPastedAction, inspectAction, actionError } from './action.mjs';
import config from './public-config.json';

const auth = getAuth(initializeApp(config));
const title = document.querySelector('h1');
const description = document.querySelector('#description');
const form = document.querySelector('#action-form');
const button = form.querySelector('button');
const recoveryForm = document.querySelector('#recovery-form');
const recoveryLink = document.querySelector('#email-link');
const recoveryButton = recoveryForm.querySelector('button');
const password = document.querySelector('#password');
const confirm = document.querySelector('#confirm');
const status = document.querySelector('#status');
let action;
// Remove codes from the address bar/history before loading any external resource.
const original = location.href;
history.replaceState(null, '', location.pathname);
const showError = error => { status.textContent = actionError(error); button.disabled = false; };
async function loadAction(candidate) {
  action = await inspectAction(candidate, { checkActionCode: code => checkActionCode(auth, code) });
  document.querySelector('#password-fields').hidden = action.mode !== 'resetPassword';
  if (action.mode === 'resetPassword') {
    title.textContent = 'A fresh start.';
    description.textContent = 'Choose a new password for your VisiDock account.';
    document.querySelector('#password-fields').hidden = false;
    button.textContent = 'Save new password';
  } else if (action.mode === 'recoverEmail') {
    title.textContent = 'Restore your email.';
    description.textContent = 'Confirm to restore the previous email address on your account. If you did not request the change, reset your password afterward.';
    button.textContent = 'Restore email address';
  } else {
    title.textContent = 'Make it official.';
    description.textContent = 'Confirm this email address for your VisiDock account.';
    button.textContent = 'Verify email address';
  }
  form.hidden = false;
  recoveryForm.hidden = true;
  status.textContent = '';
}
try {
  await loadAction(readAction(original));
} catch (error) {
  title.textContent = 'Let’s try a fresh link.';
  description.textContent = actionError(error);
  recoveryForm.hidden = false;
}
recoveryForm.addEventListener('submit', async event => {
  event.preventDefault();
  recoveryButton.disabled = true;
  try {
    const candidate = readPastedAction(recoveryLink.value);
    recoveryLink.value = '';
    await loadAction(candidate);
  } catch (error) {
    recoveryLink.value = '';
    status.textContent = actionError(error);
  } finally { recoveryButton.disabled = false; }
});
form.addEventListener('submit', async event => {
  event.preventDefault();
  if (action.mode === 'resetPassword' && (password.value.length < 8 || password.value !== confirm.value)) {
    status.textContent = password.value.length < 8 ? 'Use at least 8 characters for your new password.' : 'The passwords do not match.';
    return;
  }
  button.disabled = true;
  status.textContent = 'Updating your account…';
  try {
    if (action.mode === 'resetPassword') await confirmPasswordReset(auth, action.code, password.value);
    else await applyActionCode(auth, action.code);
    password.value = ''; confirm.value = ''; form.hidden = true;
    title.textContent = action.mode === 'resetPassword' ? 'Password updated.' : action.mode === 'recoverEmail' ? 'Email restored.' : 'You’re verified.';
    description.textContent = action.mode === 'verifyEmail' ? 'Return to VisiDock and tap “I’ve verified” to refresh your account.' : 'Return to VisiDock and sign in with your updated details.';
    status.textContent = '';
  } catch (error) { showError(error); }
});
