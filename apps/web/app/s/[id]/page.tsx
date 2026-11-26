import type { Metadata } from "next";
import "maplibre-gl/dist/maplibre-gl.css";
import ShareViewer from "../../../src/components/viewer/ShareViewer";

export const dynamic = "force-dynamic";
export const revalidate = 0;

export const metadata: Metadata = {
  title: "Live trip | OpenTrack360",
  description: "A private, read-only OpenTrack360 trip link.",
  robots: { index: false, follow: false, nocache: true },
  referrer: "no-referrer",
};

export default async function SharePage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;

  return <ShareViewer shareId={id} />;
}
