/// <reference path="../pb_data/types.d.ts" />
// One record per photo, with its capture mask and segment metadata (D42). Append-only through the API: the app
// creates records but can't change or delete them, so an upload can never drop evidence. The legacy
// receipts.photos field is no longer written by the app. Deleting a receipt (superusers only) deletes its photos.
migrate((app) => {
  const authed = '@request.auth.id != ""'
  const receipts = app.findCollectionByNameOrId("receipts")
  const photos = new Collection({
    type: "base",
    name: "receipt_photos",
    listRule: authed,
    viewRule: authed,
    createRule: authed,
    updateRule: null,
    deleteRule: null,
    fields: [
      { name: "receipt", type: "relation", collectionId: receipts.id, maxSelect: 1, required: true, cascadeDelete: true },
      { name: "local_name", type: "text", required: true, max: 200, pattern: "^[A-Za-z0-9._-]+$" },
      { name: "session_id", type: "text", max: 64 },
      { name: "image", type: "file", required: true, maxSelect: 1, maxSize: 30 * 1024 * 1024, mimeTypes: ["image/jpeg"] },
      { name: "mask", type: "file", maxSelect: 1, maxSize: 5 * 1024 * 1024, mimeTypes: ["image/png"] },
      { name: "segment", type: "json", maxSize: 200000 },
      { name: "captured_at", type: "date" },
      { name: "created", type: "autodate", onCreate: true, onUpdate: false },
      { name: "updated", type: "autodate", onCreate: true, onUpdate: true },
    ],
    indexes: ["CREATE UNIQUE INDEX idx_receipt_photos_local ON receipt_photos (receipt, local_name)"],
  })
  app.save(photos)
}, (app) => {
  const photos = app.findCollectionByNameOrId("receipt_photos")
  if (app.findRecordsByFilter("receipt_photos", "", "", 1, 0).length > 0)
    throw new Error("Cannot drop receipt_photos while it holds photos")
  app.delete(photos)
})
