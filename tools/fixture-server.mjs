// Local acceptance fixture only. No npm packages are required.
import http from 'node:http';
import { readFile } from 'node:fs/promises';

const port = Number(process.env.MIRU_FIXTURE_PORT || 8743);
const pages = new Map([
  ['/', 'first.html'], ['/first', 'first.html'], ['/first.html', 'first.html'],
  ['/second', 'second.html'], ['/second.html', 'second.html'],
]);
http.createServer(async (request, response) => {
  const page = pages.get(new URL(request.url, 'http://localhost').pathname);
  if (!page) { response.writeHead(404); response.end(); return; }
  try {
    const body = await readFile(new URL(`./fixtures/${page}`, import.meta.url));
    response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    response.end(body);
  } catch {
    response.writeHead(500); response.end();
  }
}).listen(port, '127.0.0.1', () => process.stdout.write(`Miru test pages: http://127.0.0.1:${port}/first\n`));
