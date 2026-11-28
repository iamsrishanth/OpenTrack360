const NO_STORE_HEADERS = {
  "Cache-Control": "private, no-store, max-age=0",
  "Referrer-Policy": "no-referrer",
  "X-Robots-Tag": "noindex, nofollow, noarchive",
  "Content-Security-Policy": "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
};

const FALLBACK_PAGE = `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="robots" content="noindex, nofollow, noarchive">
    <title>Return to OpenTrack360</title>
    <style>
      :root { color-scheme: light; font-family: system-ui, sans-serif; color: #20221e; background: #f6f7f3; }
      body { margin: 0; min-height: 100vh; display: grid; place-items: center; padding: 24px; box-sizing: border-box; }
      main { width: min(100%, 520px); box-sizing: border-box; padding: 36px; border: 1px solid #e4e7e0; border-radius: 18px; background: #fff; box-shadow: 0 18px 60px #14210c0f; }
      p { color: #5e665a; line-height: 1.65; }
      .eyebrow { color: #427b2c; font-size: 12px; font-weight: 700; letter-spacing: .12em; }
      a { color: #336c20; font-weight: 650; }
      @media (max-width: 520px) { main { padding: 28px 24px; } }
    </style>
  </head>
  <body>
    <main>
      <p class="eyebrow">SECURE SIGN IN</p>
      <h1>Return to the OpenTrack360 app</h1>
      <p>Your sign-in is ready to finish in OpenTrack360. If the app did not open, return to it and start sign-in again.</p>
      <p>This page does not display or store your authorization response.</p>
      <p><a href="/">OpenTrack360 home</a></p>
    </main>
  </body>
</html>`;

export async function GET(request: Request): Promise<Response> {
  const requestUrl = new URL(request.url);
  if (requestUrl.search) {
    const cleanUrl = new URL("/oauth/callback", requestUrl.origin);
    return new Response(null, {
      status: 303,
      headers: { ...NO_STORE_HEADERS, Location: cleanUrl.toString() },
    });
  }

  return new Response(FALLBACK_PAGE, {
    status: 200,
    headers: { ...NO_STORE_HEADERS, "Content-Type": "text/html; charset=utf-8" },
  });
}
