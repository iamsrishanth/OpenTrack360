import type { Metadata } from "next";
import CircleInviteHandoff from "@/src/components/invite/CircleInviteHandoff";

export const metadata: Metadata = {
  title: "Circle invitation",
  description: "Open a private OpenTrack360 Circle invitation.",
  robots: { index: false, follow: false, noarchive: true },
};

export default function CircleInvitePage() {
  return <CircleInviteHandoff />;
}
