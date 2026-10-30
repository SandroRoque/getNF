// Where Supabase's confirmation link lands (D120). Supabase answers in the address: after "#" on success
// (access_token, type=signup) or failure (error, error_code, error_description), sometimes after "?" for errors.
// The page only reads which; the tokens are dropped from the address at once and never kept or sent anywhere: the
// person signs in on the app.
(function () {
  var hash = new URLSearchParams(location.hash.slice(1));
  var query = new URLSearchParams(location.search);
  var get = function (k) { return hash.get(k) || query.get(k); };
  var shown = null;
  if (get("error") || get("error_code")) shown = "erro";
  else if (hash.get("access_token")) shown = "ok";
  if (location.hash || location.search) history.replaceState(null, "", location.pathname);
  if (!shown) return;
  document.getElementById("neutro").hidden = true;
  document.getElementById(shown).hidden = false;
  var code = get("error_code");
  if (shown === "erro" && code && code !== "otp_expired") {
    var detail = document.getElementById("detalhe");
    detail.textContent = "Detalhe: " + (get("error_description") || code);
    detail.hidden = false;
  }
})();
