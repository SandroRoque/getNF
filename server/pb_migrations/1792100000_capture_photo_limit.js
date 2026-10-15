/// <reference path="../pb_data/types.d.ts" />
// Continuous capture archives overlapping segments instead of a single whole-document photograph.
migrate((app) => {
  const receipts = app.findCollectionByNameOrId("receipts")
  receipts.fields.getByName("photos").maxSelect = 100
  app.save(receipts)
}, (app) => {
  for (let offset = 0; ; offset += 100) {
    const records = app.findRecordsByFilter("receipts", "", "id", 100, offset)
    if (records.some((r) => r.getStringSlice("photos").length > 20))
      throw new Error("Cannot restore the 20-photo limit while longer captures exist")
    if (records.length < 100) break
  }
  const receipts = app.findCollectionByNameOrId("receipts")
  receipts.fields.getByName("photos").maxSelect = 20
  app.save(receipts)
})
