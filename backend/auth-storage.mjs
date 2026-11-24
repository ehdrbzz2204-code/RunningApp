export function createAuthStorage(db) {
  db.exec(`
    CREATE TABLE IF NOT EXISTS users (
      id TEXT PRIMARY KEY NOT NULL,
      email TEXT UNIQUE NOT NULL,
      password_salt BLOB NOT NULL,
      password_hash BLOB NOT NULL
    ) STRICT;
    CREATE TABLE IF NOT EXISTS auth_sessions (
      token_hash TEXT PRIMARY KEY NOT NULL,
      user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      expires_at INTEGER NOT NULL
    ) STRICT;
    CREATE INDEX IF NOT EXISTS auth_sessions_expiry ON auth_sessions(expires_at);
  `);

  const insertUser = db.prepare(`
    INSERT INTO users (id, email, password_salt, password_hash) VALUES (?, ?, ?, ?)
    ON CONFLICT (email) DO NOTHING
  `);
  const selectUser = db.prepare(`
    SELECT id, email, password_salt AS salt, password_hash AS hash
    FROM users WHERE email = ?
  `);
  const insertSession = db.prepare(`
    INSERT INTO auth_sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)
  `);
  const selectSession = db.prepare(`
    SELECT users.id, users.email
    FROM auth_sessions JOIN users ON users.id = auth_sessions.user_id
    WHERE auth_sessions.token_hash = ? AND auth_sessions.expires_at > ?
  `);
  const removeSession = db.prepare("DELETE FROM auth_sessions WHERE token_hash = ?");
  const removeExpiredSessions = db.prepare("DELETE FROM auth_sessions WHERE expires_at <= ?");

  return {
    createUser(user, salt, hash) {
      return insertUser.run(user.id, user.email, salt, hash).changes === 1;
    },
    findUserByEmail(email) {
      return selectUser.get(email);
    },
    createSession(hash, userId, expiresAt, now) {
      removeExpiredSessions.run(now);
      insertSession.run(hash, userId, expiresAt);
    },
    findSessionUser(hash, now) {
      return selectSession.get(hash, now) ?? null;
    },
    deleteSession(hash) {
      removeSession.run(hash);
    },
  };
}
