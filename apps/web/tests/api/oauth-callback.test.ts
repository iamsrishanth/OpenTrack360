import assert from "node:assert/strict";
import test from "node:test";
import { GET } from "../../app/oauth/callback/route";

test("OAuth callback removes the authorization response before serving fallback content", async () => {
  const response = await GET(new Request(
    "https://track.srishanth.me/oauth/callback?code=callback-fixture-do-not-use&state=fixture-state",
  ));

  assert.equal(response.status, 303);
  assert.equal(response.headers.get("location"), "https://track.srishanth.me/oauth/callback");
  assert.equal(response.headers.get("cache-control"), "private, no-store, max-age=0");
  assert.equal(await response.text(), "");
});

test("clean OAuth callback serves a static fallback with no callback value", async () => {
  const response = await GET(new Request("https://track.srishanth.me/oauth/callback"));
  const html = await response.text();

  assert.equal(response.status, 200);
  assert.match(html, /Return to the OpenTrack360 app/);
  assert.doesNotMatch(html, /callback-fixture-do-not-use|fixture-state/);
  assert.equal(response.headers.get("referrer-policy"), "no-referrer");
});
