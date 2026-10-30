// Where the reset-password e-mail's link lands (D123). Supabase puts a short-lived session in the address
// (#access_token=…&type=recovery), or an error. The token stays in memory only, is dropped from the address at once,
// and is used once: to set the new password (PUT /auth/v1/user). The person then signs in on the app.
"use strict";
(function () {
  const cfg = window.GETNF;
  const $ = (id) => document.getElementById(id);
  const hash = new URLSearchParams(location.hash.slice(1));
  const query = new URLSearchParams(location.search);
  const get = (k) => hash.get(k) || query.get(k);
  const token = hash.get("access_token");
  if (location.hash || location.search) history.replaceState(null, "", location.pathname);

  function show(id) {
    for (const s of ["neutro", "form", "ok", "erro"]) $(s).hidden = s !== id;
  }
  function expired(detail) {
    show("erro");
    if (detail) { $("detalhe").textContent = "Detalhe: " + detail; $("detalhe").hidden = false; }
  }

  const code = get("error_code");
  if (get("error") || code) return expired(code && code !== "otp_expired" ? (get("error_description") || code) : null);
  if (!token) return;
  show("form");
  try {
    // Only to say whose account it is; nothing in the token is trusted beyond that.
    const claims = JSON.parse(atob(token.split(".")[1].replace(/-/g, "+").replace(/_/g, "/")));
    if (claims.email) $("quem").textContent = "Conta: " + claims.email;
  } catch (e) { /* not shown */ }

  $("form").addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const senha = $("senha").value, repita = $("repita").value;
    if (senha.length < 6) return ($("estado").textContent = "Use pelo menos 6 caracteres.");
    if (senha !== repita) return ($("estado").textContent = "As senhas não são iguais.");
    $("salvar").disabled = true;
    $("estado").textContent = "Salvando…";
    try {
      const r = await fetch(cfg.url + "/auth/v1/user", {
        method: "PUT",
        headers: { apikey: cfg.key, authorization: "Bearer " + token, "content-type": "application/json" },
        body: JSON.stringify({ password: senha }),
      });
      const data = await r.json().catch(() => null);
      if (r.ok) return show("ok");
      const c = data && (data.error_code || data.code);
      if (r.status === 401 || r.status === 403 || c === "bad_jwt" || c === "session_not_found") return expired(null);
      $("estado").textContent =
        c === "same_password" ? "Essa já é a sua senha. Escolha outra, ou volte ao app e entre com ela." :
        c === "weak_password" ? "Senha fraca: use pelo menos 6 caracteres." :
        "Não deu certo: " + ((data && (data.msg || data.message)) || "HTTP " + r.status);
    } catch (e) {
      $("estado").textContent = "Sem conexão com o servidor. Tente de novo.";
    }
    $("salvar").disabled = false;
  });
})();
