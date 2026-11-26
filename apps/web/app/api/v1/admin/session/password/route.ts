import { authFaultResponse, readAdminCookie, requireAdminCsrf, requireAdminSession } from "@/src/server/auth/member";
import { parseJsonObject } from "@/src/server/auth/supabase";
import { POST as changeMemberPassword } from "@/app/api/v1/me/password/route";

function expired(name: string): string {
  return `${name}=; Path=/api/v1/admin; Max-Age=0; Secure; HttpOnly; SameSite=Strict`;
}

export async function POST(request: Request): Promise<Response> {
  try {
    requireAdminCsrf(request);
    await requireAdminSession(request, { allowPasswordRotation: true });
    const accessToken = readAdminCookie(request, "ot_admin_access");
    const body = await parseJsonObject(request);
    if (!accessToken || !body) return Response.json({ error: { code: "invalid_request", message: "Invalid request" } }, { status: 400, headers: { "Cache-Control": "no-store" } });
    const headers = new Headers(request.headers);
    headers.set("authorization", `Bearer ${accessToken}`);
    headers.set("content-type", "application/json");
    const forwarded = new Request(request.url, { method: "POST", headers, body: JSON.stringify(body) });
    const response = await changeMemberPassword(forwarded);
    const headersOut = new Headers(response.headers);
    headersOut.append("Set-Cookie", expired("ot_admin_access"));
    headersOut.append("Set-Cookie", expired("ot_admin_refresh"));
    return new Response(await response.text(), { status: response.status, headers: headersOut });
  } catch (error) {
    return authFaultResponse(error);
  }
}
