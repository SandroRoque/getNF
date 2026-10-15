export class AuthError extends Error {}
class LoginError extends Error {}

export function createApi({ fetcher = fetch, base = "/api" } = {}) {
  async function request(path, { token, method = "GET", body, signal } = {}) {
    const response = await fetcher(`${base}${path}`, {
      method, signal, cache: "no-store",
      headers: { ...(token ? { Authorization: token } : {}), ...(body ? { "Content-Type": "application/json" } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}),
    })
    if (response.status === 401 || response.status === 403) throw new AuthError("Sua sessão expirou. Entre novamente.")
    if (response.status === 400 && path.endsWith("/auth-with-password")) throw new LoginError("E-mail ou senha incorretos.")
    if (!response.ok) throw new Error(`O servidor não conseguiu carregar esta solicitação (HTTP ${response.status}). Tente novamente.`)
    return response.json()
  }

  async function all(collection, fields, token, signal) {
    const rows = []
    for (let page = 1; ; page++) {
      const params = new URLSearchParams({ page, perPage: 500, fields, sort: "id" })
      const result = await request(`/collections/${collection}/records?${params}`, { token, signal })
      if (!Array.isArray(result.items) || !Number.isInteger(result.totalPages)) throw new Error("O servidor retornou dados incompletos. Tente novamente.")
      rows.push(...result.items)
      if (page >= result.totalPages) return rows
    }
  }

  return {
    login: async (identity, password, signal) => {
      const options = { method: "POST", body: { identity, password }, signal }
      try {
        return { ...await request("/collections/users/auth-with-password", options), collection: "users" }
      } catch (error) {
        // The same origin also hosts PocketBase admin; password managers may save that login.
        // Try it only after an explicit invalid-credentials response, never a network/server error.
        if (!(error instanceof LoginError)) throw error
        return { ...await request("/collections/_superusers/auth-with-password", options), collection: "_superusers" }
      }
    },
    refreshAuth: (token, signal, collection = "users") => {
      if (!["users", "_superusers"].includes(collection)) throw new AuthError("Entre novamente.")
      return request(`/collections/${collection}/auth-refresh`, { method: "POST", token, signal })
    },
    load: async (token, signal) => {
      const [lines, categories, receipts] = await Promise.all([
        all("spending_lines", "id,month,day,category,product,description,qty,unit,net,store_name,discount_source,receipt", token, signal),
        all("categories", "id,path,label,order", token, signal),
        all("receipts", "id,store_name,issued_at,total_paid,parse_error", token, signal),
      ])
      return { lines, categories, receipts }
    },
  }
}
