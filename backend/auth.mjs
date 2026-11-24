import { createHash, randomBytes, randomUUID, scrypt, timingSafeEqual } from "node:crypto";
import { promisify } from "node:util";

const deriveKey = promisify(scrypt);
const passwordOptions = { N: 2 ** 15, r: 8, p: 3, maxmem: 64 * 1024 * 1024 };
const sessionLifetimeMs = 7 * 24 * 60 * 60 * 1000;
const dummySalt = randomBytes(16);
const dummyHash = randomBytes(64);

export function normalizeCredentials(input) {
  if (typeof input?.email !== "string" || typeof input?.password !== "string") return null;
  const email = input.email.trim().toLowerCase();
  const password = input.password;
  if (email.length > 254 || !/^[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9-]+(?:\.[a-z0-9-]+)+$/.test(email) ||
      !password.isWellFormed() || password.length === 0 || [...password].length > 128) {
    return null;
  }
  return { email, password };
}

export function normalizeRegistration(input) {
  const credentials = normalizeCredentials(input);
  return credentials && [...credentials.password].length >= 15 ? credentials : null;
}

function tokenHash(token) {
  return createHash("sha256").update(token).digest("hex");
}

export function createAuth(storage) {
  return {
    async register({ email, password }) {
      const user = { id: randomUUID(), email };
      const salt = randomBytes(16);
      const hash = await deriveKey(password, salt, 64, passwordOptions);
      return storage.createUser(user, salt, hash) ? user : null;
    },

    async login({ email, password }) {
      const account = storage.findUserByEmail(email);
      const candidate = await deriveKey(password, account?.salt ?? dummySalt, 64, passwordOptions);
      if (!timingSafeEqual(candidate, account?.hash ?? dummyHash) || !account) return null;

      const accessToken = randomBytes(32).toString("base64url");
      const now = Date.now();
      const expiresAt = now + sessionLifetimeMs;
      storage.createSession(tokenHash(accessToken), account.id, expiresAt, now);
      return {
        accessToken,
        tokenType: "Bearer",
        expiresAt: new Date(expiresAt).toISOString(),
        user: { id: account.id, email: account.email },
      };
    },

    authenticate(token) {
      if (!token || !/^[A-Za-z0-9_-]{43}$/.test(token)) return null;
      return storage.findSessionUser(tokenHash(token), Date.now());
    },

    logout(token) {
      storage.deleteSession(tokenHash(token));
    },
  };
}
