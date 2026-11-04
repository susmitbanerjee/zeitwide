import type { Crumb, PageModel } from "./page-models";
import { ORGANIZATION, DATASET_LICENSE, NODE_IDS } from "./site-identity";
import { Graph, type GraphNode } from "./structured-data-schema";

/**
 * One JSON-LD @graph per public page, built from the page's own model, so it states only what the
 * page shows: the Organization and WebSite from the header and footer, the page and its
 * breadcrumb trail, and per template the Dataset, the question the AnswerLead answers and the
 * visible FAQ. Nodes link to each other by @id and are never repeated inline. Every graph is
 * parsed against the strict schemas before it is returned, so an invalid one throws.
 */

const ref = (id: string) => ({ "@id": id });

/** The publisher and the site, on every page: the header and footer show both. */
function siteNodes(siteUrl: string): GraphNode[] {
  const home = new URL("/", siteUrl).toString();
  return [
    {
      "@type": "Organization",
      "@id": NODE_IDS.organization(siteUrl),
      name: ORGANIZATION.name,
      url: home,
      logo: new URL(ORGANIZATION.logoPath, siteUrl).toString(),
      sameAs: ORGANIZATION.profiles.map((profile) => profile.href),
    },
    {
      "@type": "WebSite",
      "@id": NODE_IDS.website(siteUrl),
      name: ORGANIZATION.name,
      url: home,
      publisher: ref(NODE_IDS.organization(siteUrl)),
    },
  ];
}

/** The visible trail, linked page by page; the last crumb is this page. */
function breadcrumbNode(url: string, crumbs: Crumb[], siteUrl: string): GraphNode {
  return {
    "@type": "BreadcrumbList",
    "@id": `${url}#breadcrumb`,
    itemListElement: crumbs.map((crumb, index) => ({
      "@type": "ListItem",
      position: index + 1,
      name: crumb.label,
      item: crumb.href === undefined ? url : new URL(crumb.href, siteUrl).toString(),
    })),
  };
}

interface PageNodeInput {
  url: string;
  title: string;
  description?: string;
  crumbs: Crumb[];
}

function webPageNode(page: PageNodeInput, siteUrl: string) {
  return {
    "@type": "WebPage" as const,
    "@id": `${page.url}#webpage`,
    url: page.url,
    name: page.title,
    ...(page.description === undefined ? {} : { description: page.description }),
    isPartOf: ref(NODE_IDS.website(siteUrl)),
    ...(page.crumbs.length === 0 ? {} : { breadcrumb: ref(`${page.url}#breadcrumb`) }),
  };
}

function graph(nodes: GraphNode[]): Graph {
  return Graph.parse({ "@context": "https://schema.org", "@graph": nodes });
}

/** The graph for a public data page (answer, year, calendar, compare, index, sources, pricing). */
export function pageGraph(model: PageModel, siteUrl: string): Graph {
  const page = webPageNode(
    { url: model.url, title: model.title, description: model.lead, crumbs: model.crumbs },
    siteUrl,
  );
  const nodes: GraphNode[] = [...siteNodes(siteUrl)];
  const sourceUrls = [...new Set(model.sources.map((source) => source.url))];
  switch (model.type) {
    case "answer": {
      const questionId = `${model.url}#question`;
      const published = model.revision.published;
      nodes.push(
        {
          ...page,
          about: ref(model.datasetId),
          mainEntity: ref(questionId),
          ...(published === null ? {} : { dateModified: published.iso }),
          ...(sourceUrls.length === 0 ? {} : { citation: sourceUrls }),
        },
        {
          "@type": "Question",
          "@id": questionId,
          name: model.title,
          acceptedAnswer: { "@type": "Answer", text: model.lead },
        },
      );
      break;
    }
    case "calendar": {
      const { dataset } = model;
      const published = dataset.revision.published;
      nodes.push(
        { ...page, about: ref(dataset.id), mainEntity: ref(dataset.id) },
        {
          "@type": "Dataset",
          "@id": dataset.id,
          name: model.title,
          description: model.lead,
          url: model.url,
          license: DATASET_LICENSE.url,
          creator: ref(NODE_IDS.organization(siteUrl)),
          ...(dataset.coverage === null
            ? {}
            : { temporalCoverage: `${dataset.coverage.from.iso}/${dataset.coverage.to.iso}` }),
          ...(published === null ? {} : { dateModified: published.iso }),
          version: String(dataset.revision.revision),
          distribution: dataset.downloads.map((download) => ({
            "@type": "DataDownload",
            encodingFormat: download.encodingFormat,
            contentUrl: new URL(download.href, siteUrl).toString(),
          })),
          ...(sourceUrls.length === 0 ? {} : { isBasedOn: sourceUrls }),
          variableMeasured: "business day status",
        },
      );
      break;
    }
    case "year":
      nodes.push({ ...page, about: ref(model.datasetId) });
      break;
    case "compare":
      nodes.push({ ...page, about: model.datasetIds.map(ref) });
      break;
    case "calendars":
    case "sources":
    case "pricing":
      nodes.push(page);
      break;
  }
  if (model.faqs.length > 0) {
    // Only the questions the FAQ section shows; the page's own question is its mainEntity.
    nodes.push({
      "@type": "FAQPage",
      "@id": `${model.url}#faq`,
      isPartOf: ref(page["@id"]),
      mainEntity: model.faqs.map((faq) => ({
        "@type": "Question",
        name: faq.question,
        acceptedAnswer: { "@type": "Answer", text: faq.answer },
      })),
    });
  }
  nodes.push(breadcrumbNode(model.url, model.crumbs, siteUrl));
  return graph(nodes);
}

/** A page with no page model: the home page, a docs page or the API reference. */
export interface ContentPage {
  url: string;
  title: string;
  description?: string;
  /** Empty on the home page, which has no trail. */
  crumbs: Crumb[];
  /** The API reference also describes the API itself. */
  describesApi?: { name: string };
}

export function contentGraph(content: ContentPage, siteUrl: string): Graph {
  const nodes: GraphNode[] = [...siteNodes(siteUrl)];
  const page = webPageNode(content, siteUrl);
  if (content.describesApi === undefined) {
    nodes.push(page);
  } else {
    nodes.push(
      { ...page, about: ref(NODE_IDS.api(siteUrl)) },
      {
        "@type": "WebAPI",
        "@id": NODE_IDS.api(siteUrl),
        name: content.describesApi.name,
        documentation: new URL("/docs", siteUrl).toString(),
        provider: ref(NODE_IDS.organization(siteUrl)),
      },
    );
  }
  if (content.crumbs.length > 0) {
    nodes.push(breadcrumbNode(content.url, content.crumbs, siteUrl));
  }
  return graph(nodes);
}
