/**
 * DOM wiring for the FinTech customer UI. All platform logic lives in api.js; this file only
 * reads inputs, calls the client, and renders results. It is intentionally dependency-free:
 * no framework, no build step, served as static files.
 */
import { ApiClient, ApiError, formatMoney, loadConfig, newIdempotencyKey } from './api.js';

const $ = (id) => document.getElementById(id);

let api = null;
let customerId = null;

function showError(box, error) {
  const el = $(box);
  if (error instanceof ApiError && error.correlationId) {
    el.textContent = `${error.message} (ref ${error.correlationId})`;
  } else if (error instanceof Error) {
    el.textContent = error.message;
  } else {
    el.textContent = String(error);
  }
  el.hidden = false;
}

function clearError(box) {
  $(box).hidden = true;
}

/** Disables the submit button while the call is in flight: no double-click double-pay. */
async function guarded(form, fn) {
  const button = form.querySelector('button[type="submit"]');
  button.disabled = true;
  try {
    await fn();
  } finally {
    button.disabled = false;
  }
}

async function boot() {
  const config = await loadConfig();
  api = new ApiClient(config);

  $('login-form').addEventListener('submit', (event) => {
    event.preventDefault();
    clearError('auth-error');
    guarded(event.target, async () => {
      try {
        await api.login($('login-email').value.trim(), $('login-password').value);
        await enter();
      } catch (error) {
        showError('auth-error', error);
      }
    });
  });

  $('register-form').addEventListener('submit', (event) => {
    event.preventDefault();
    clearError('auth-error');
    guarded(event.target, async () => {
      try {
        // The login identity and the customer profile are separate steps on purpose: Keycloak
        // authenticates, customer-service onboards. Registration here only creates the Keycloak
        // side implicitly — the operator provisions the login first (see scripts), then this
        // form creates the profile, runs the synthetic identity check, and signs in.
        const email = $('reg-email').value.trim();
        const password = $('reg-password').value;
        await api.login(email, password);
        const profile = await api.registerCustomer({
          fullName: $('reg-name').value.trim(),
          dateOfBirth: $('reg-dob').value,
          nationality: 'GB',
          email,
          phone: $('reg-phone').value.trim(),
          address: {
            line1: $('reg-line1').value.trim(),
            city: $('reg-city').value.trim(),
            postalCode: $('reg-postcode').value.trim(),
            country: 'GB',
          },
        });
        await api.submitKyc(profile.id, {
          documentReference: 'SYNTH-0001',
          printedName: $('reg-name').value.trim(),
          expiryDate: '2035-01-01',
          issuingCountry: 'GB',
          nationality: 'GB',
        });
        await enter();
      } catch (error) {
        showError('auth-error', error);
      }
    });
  });

  document.querySelectorAll('#tabs button[data-tab]').forEach((tab) => {
    tab.addEventListener('click', () => switchTab(tab.dataset.tab));
  });
  $('logout').addEventListener('click', () => {
    api.logout();
    customerId = null;
    $('app-view').hidden = true;
    $('auth-view').hidden = false;
  });

  $('balance-currency').addEventListener('change', refreshOverview);
  $('fund-form').addEventListener('submit', (event) => {
    event.preventDefault();
    clearError('app-error');
    // One key for this click. If the network drops the response, the same key is NOT reused
    // automatically — the user clicks again and gets a new key — because silently replaying a
    // top-up the user may not have meant is worse than asking them to confirm.
    const key = newIdempotencyKey();
    guarded(event.target, async () => {
      try {
        await api.fund($('fund-amount').value.trim(), $('fund-currency').value, key);
        event.target.reset();
        await refreshOverview();
      } catch (error) {
        showError('app-error', error);
      }
    });
  });

  $('pay-form').addEventListener('submit', (event) => {
    event.preventDefault();
    clearError('app-error');
    const key = newIdempotencyKey();
    guarded(event.target, async () => {
      try {
        const payment = await api.pay(
          {
            amount: $('pay-amount').value.trim(),
            currency: $('pay-currency').value,
            cardToken: $('pay-token').value.trim(),
            payeeName: $('pay-payee').value.trim(),
            payeeReference: `web-${key.slice(0, 8)}`,
          },
          key,
        );
        // Capture immediately: authorisation holds, settlement moves. The two-step shape mirrors
        // the API rather than hiding it, so a held-but-unsettled payment reads as what it is.
        await api.settle(payment.id);
        event.target.reset();
        await refreshAll();
      } catch (error) {
        showError('app-error', error);
      }
    });
  });

  $('issue-form').addEventListener('submit', (event) => {
    event.preventDefault();
    clearError('app-error');
    guarded(event.target, async () => {
      try {
        const issued = await api.issueCard(customerId);
        // Shown once, by design: the number exists in exactly one response and the platform
        // never stores it. Copy it now — refreshing loses it forever.
        $('issue-result').innerHTML = '';
        const shown = document.createElement('p');
        shown.innerHTML = `New card <span class="card-number"></span> — copy it now, it will never be shown again.`;
        shown.querySelector('.card-number').textContent = issued.cardNumber;
        $('issue-result').append(shown);
        await refreshCards();
      } catch (error) {
        showError('app-error', error);
      }
    });
  });

  $('dispute-form').addEventListener('submit', (event) => {
    event.preventDefault();
    clearError('app-error');
    guarded(event.target, async () => {
      try {
        await api.openDispute($('dispute-txn').value, $('dispute-reason').value, $('dispute-desc').value.trim());
        event.target.reset();
        await refreshDisputes();
      } catch (error) {
        showError('app-error', error);
      }
    });
  });
}

async function enter() {
  const me = await api.myProfile();
  customerId = me.id;
  $('auth-view').hidden = true;
  $('app-view').hidden = false;
  $('session').textContent = me.email || '';
  switchTab('overview');
  await refreshAll();
}

function switchTab(name) {
  document.querySelectorAll('#tabs button[data-tab]').forEach((tab) => {
    tab.classList.toggle('active', tab.dataset.tab === name);
  });
  document.querySelectorAll('[data-pane]').forEach((pane) => {
    pane.hidden = pane.dataset.pane !== name;
  });
  if (name === 'pay' && !$('pay-token').value) {
    // A fresh stand-in token per visit to the Pay tab. It is the wallet token's understudy in
    // this demo — generated client-side like the API scripts generate theirs.
    $('pay-token').value = `tok_web_${newIdempotencyKey().replace(/-/g, '').slice(0, 16)}`;
  }
}

async function refreshAll() {
  await Promise.all([refreshOverview(), refreshCards(), refreshActivity(), refreshDisputes()]);
}

async function refreshOverview() {
  try {
    const me = await api.myProfile();
    $('profile').textContent = `${me.fullName || ''} — identity check: ${me.kycStatus || 'unknown'}`;
    const currency = $('balance-currency').value;
    const balances = await api.balance(currency);
    $('balances').textContent = `Available ${formatMoney(balances.available, currency)} · held ${formatMoney(balances.held, currency)}`;
  } catch (error) {
    showError('app-error', error);
  }
}

async function refreshCards() {
  try {
    const cards = await api.listCards();
    const list = $('cards');
    list.innerHTML = '';
    for (const card of cards) {
      const item = document.createElement('li');
      item.className = 'card';
      const label = document.createElement('span');
      label.innerHTML = `Card <span class="mono"></span> `;
      label.querySelector('.mono').textContent = `…${card.last4}`;
      const status = document.createElement('span');
      status.className = 'tag';
      status.textContent = card.status;
      item.append(label, status);
      if (card.status === 'ACTIVE') {
        for (const [action, text] of [['freeze', 'Freeze'], ['lost', 'Report lost']]) {
          const button = document.createElement('button');
          button.textContent = text;
          button.addEventListener('click', async () => {
            try {
              await api.cardAction(card.id, action);
              await refreshCards();
            } catch (error) {
              showError('app-error', error);
            }
          });
          item.append(button);
        }
      }
      if (card.status === 'FROZEN') {
        const button = document.createElement('button');
        button.textContent = 'Unfreeze';
        button.addEventListener('click', async () => {
          try {
            await api.cardAction(card.id, 'unfreeze');
            await refreshCards();
          } catch (error) {
            showError('app-error', error);
          }
        });
        item.append(button);
      }
      list.append(item);
    }
    if (cards.length === 0) {
      list.innerHTML = '<li>No cards yet — issue one above.</li>';
    }
  } catch (error) {
    showError('app-error', error);
  }
}

async function refreshActivity() {
  try {
    const { items } = await api.listTransactions(20);
    const box = $('transactions');
    box.innerHTML = '';
    const list = document.createElement('ul');
    for (const txn of items || []) {
      const item = document.createElement('li');
      item.className = 'row';
      const text = document.createElement('span');
      text.textContent = `${txn.payeeName || 'Payment'} — ${formatMoney(txn.amount, txn.currency)} `;
      const status = document.createElement('span');
      status.className = 'tag';
      status.textContent = txn.status;
      item.append(text, status);
      list.append(item);
    }
    box.append(list);
    const select = $('dispute-txn');
    const current = select.value;
    select.innerHTML = '';
    for (const txn of items || []) {
      if (txn.status !== 'SETTLED') continue;
      const option = document.createElement('option');
      option.value = txn.id;
      option.textContent = `${txn.payeeName || 'Payment'} — ${formatMoney(txn.amount, txn.currency)}`;
      select.append(option);
    }
    if (current) select.value = current;
  } catch (error) {
    showError('app-error', error);
  }
}

async function refreshDisputes() {
  try {
    const page = await api.listDisputes();
    const list = $('disputes');
    list.innerHTML = '';
    for (const dispute of page.content || []) {
      const item = document.createElement('li');
      item.className = 'row';
      const text = document.createElement('span');
      text.textContent = `${dispute.reason} — ${dispute.description} `;
      const status = document.createElement('span');
      status.className = 'tag';
      status.textContent = dispute.status;
      item.append(text, status);
      list.append(item);
    }
    if ((page.content || []).length === 0) {
      list.innerHTML = '<li>No disputes.</li>';
    }
  } catch (error) {
    showError('app-error', error);
  }
}

boot().catch((error) => showError('auth-error', error));
