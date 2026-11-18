import { existsSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { loadConfig } from './config.js';
import { createApp } from './app.js';
import { GoogleIntegrityDecoder, MockIntegrityDecoder } from './integrity/decoders.js';

// Minimal .env loader (Node 18 has no --env-file). Real env vars win over the file.
const envPath = join(dirname(fileURLToPath(import.meta.url)), '..', '.env');
if (existsSync(envPath)) {
  for (const line of readFileSync(envPath, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/);
    if (m && process.env[m[1]] === undefined) process.env[m[1]] = m[2];
  }
}

const config = loadConfig();
const decoder =
  config.integrityMode === 'mock'
    ? new MockIntegrityDecoder()
    : new GoogleIntegrityDecoder({ packageName: config.expectedPackageName });

if (config.integrityMode === 'mock') {
  console.warn('WARNING: INTEGRITY_MODE=mock — tokens are NOT verified with Google. Never use in production.');
}

const app = createApp({ config, decoder });
const server = app.listen(config.port, () => {
  console.log(`play-integrity-server listening on :${config.port} (mode=${config.integrityMode})`);
});

function shutdown() {
  server.close(() => {
    app.close();
    process.exit(0);
  });
}
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
