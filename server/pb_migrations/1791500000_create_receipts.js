/// <reference path="../pb_data/types.d.ts" />

// One record per NFC-e receipt, keyed by the 44-digit chave de acesso (see docs/decisions.md D2, D11).
// The phone uploads raw captures only: photos, the QR URL, and the details page HTML.
migrate((app) => {
  const authed = '@request.auth.id != ""'
  const receipts = new Collection({
    type: "base",
    name: "receipts",
    listRule: authed,
    viewRule: authed,
    createRule: authed,
    updateRule: authed,
    deleteRule: null, // superusers only
    fields: [
      { name: "chave", type: "text", required: true, pattern: "^[0-9]{44}$", min: 44, max: 44 },
      { name: "qr_url", type: "text", max: 2000 },
      { name: "page_url", type: "text", max: 2000 },
      { name: "captured_at", type: "date" },
      { name: "page_fetched_at", type: "date" },
      { name: "photos", type: "file", maxSelect: 20, maxSize: 30 * 1024 * 1024, mimeTypes: ["image/jpeg"] },
      { name: "page", type: "file", maxSelect: 1, maxSize: 10 * 1024 * 1024 },
      { name: "created", type: "autodate", onCreate: true, onUpdate: false },
      { name: "updated", type: "autodate", onCreate: true, onUpdate: true },
    ],
    indexes: ["CREATE UNIQUE INDEX idx_receipts_chave ON receipts (chave)"],
  })
  app.save(receipts)
}, (app) => {
  app.delete(app.findCollectionByNameOrId("receipts"))
})
