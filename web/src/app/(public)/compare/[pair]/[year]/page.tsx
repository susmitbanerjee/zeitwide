import type { Metadata } from "next";

import { publicPageMetadata, renderPublicPage } from "@/components/public/render";
import { compareParams } from "@/lib/public-data";

export const ensureStatic = "navigation";

/** Every pair of calendars, in sorted-ID order, for the current and next year. */
export async function generateStaticParams(): Promise<{ pair: string; year: string }[]> {
  return (await compareParams()).map(({ idA, idB, year }) => ({
    pair: `${idA}-vs-${idB}`,
    year: String(year),
  }));
}

async function path(params: PageProps<"/compare/[pair]/[year]">["params"]): Promise<string> {
  const { pair, year } = await params;
  return `/compare/${pair}/${year}`;
}

export async function generateMetadata({
  params,
}: PageProps<"/compare/[pair]/[year]">): Promise<Metadata> {
  return publicPageMetadata(await path(params));
}

export default async function ComparePage({ params }: PageProps<"/compare/[pair]/[year]">) {
  return renderPublicPage(await path(params));
}
