import type { Metadata } from "next";
import { notFound, permanentRedirect } from "next/navigation";

import { loadPage } from "@/lib/page-model-data";
import { markdownUrl, matchPublicPath } from "@/lib/public-routes";

import { PublicPageView } from "./public-page";

/** The HTML for a public path, from the same model as its Markdown. */
export async function renderPublicPage(path: string) {
  const route = matchPublicPath(path);
  const loaded = route === null || route.type === "doc" ? null : await loadPage(route);
  if (loaded === null) {
    notFound();
  }
  if (loaded.kind === "redirect") {
    // One URL per page, such as compare pairs in sorted-ID order.
    permanentRedirect(loaded.path);
  }
  return <PublicPageView model={loaded.model} />;
}

/** Title, description (the lead), canonical URL and the Markdown alternate for a public path. */
export async function publicPageMetadata(path: string): Promise<Metadata> {
  const route = matchPublicPath(path);
  const loaded = route === null || route.type === "doc" ? null : await loadPage(route);
  if (loaded === null || loaded.kind === "redirect") {
    return { title: "Not found", robots: { index: false } };
  }
  const { model } = loaded;
  return {
    title: model.type === "answer" ? { absolute: model.title } : model.title,
    description: model.lead,
    alternates: { canonical: model.url, types: { "text/markdown": markdownUrl(model.url) } },
    openGraph: { title: model.title, description: model.lead, url: model.url, type: "article" },
  };
}
