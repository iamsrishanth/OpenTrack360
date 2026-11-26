import type { Metadata } from "next";
import PetRecoveryViewer from "../../../src/components/viewer/PetRecoveryViewer";

export const dynamic = "force-dynamic";
export const revalidate = 0;

export const metadata: Metadata = {
  title: "Pet recovery | OpenTrack360",
  description: "A private, owner-approved pet recovery card.",
  robots: { index: false, follow: false, nocache: true },
  referrer: "no-referrer",
};

export default async function PetRecoveryPage({
  params,
}: {
  params: Promise<{ petId: string }>;
}) {
  const { petId } = await params;

  return <PetRecoveryViewer petId={petId} />;
}
