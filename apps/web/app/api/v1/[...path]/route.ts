import { requireMember } from "../../../../src/server/auth/member";
import { createApiDispatcher } from "../../../../src/server/api/dispatcher";
import { createDefaultApiServices } from "../../../../src/server/api/rpc";

export const runtime = "nodejs";

const dispatch = createApiDispatcher(createDefaultApiServices(requireMember));

export const GET = dispatch;
export const POST = dispatch;
export const PUT = dispatch;
export const PATCH = dispatch;
export const DELETE = dispatch;
