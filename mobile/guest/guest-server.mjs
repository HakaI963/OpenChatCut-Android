// OpenChatCut Android guest server entry.
// Bundles upstream `desktop/embedded-server.ts` (the exact production server the
// desktop app runs) with esbuild at packaging time and boots it inside the
// proot Debian guest. Prints one JSON line so the Android host can find the port:
//   OCC_SERVER_READY {"port":1234,"origin":"http://127.0.0.1:1234"}
import { startEmbeddedServer } from '../../desktop/embedded-server.ts';

const distDir = process.env.OCC_DIST_DIR || 'dist';

try {
  const srv = await startEmbeddedServer(distDir);
  process.stdout.write(`OCC_SERVER_READY ${JSON.stringify({ port: srv.port, origin: srv.origin })}\n`);
  process.on('SIGTERM', () => process.exit(0));
  process.on('SIGINT', () => process.exit(0));
} catch (err) {
  process.stderr.write(`OCC_SERVER_FAIL ${(err && (err.stack || err.message)) || String(err)}\n`);
  process.exit(3);
}
