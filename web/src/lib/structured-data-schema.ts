import { z } from "zod";

/**
 * The JSON-LD each public page may carry, one strict schema per schema.org type. Strict objects
 * reject any property not listed, so nothing reaches a page by accident: no ratings, reviews or
 * offers, and nothing the page doesn't show. The graph is parsed on every render, which for
 * prebuilt pages is the build, so a graph that breaks these rules fails it.
 */

const Iri = z.url({ protocol: /^https?$/ });
const Text = z.string().trim().min(1);
const Ref = z.strictObject({ "@id": Iri });
const IsoDateTime = z.iso.datetime({ offset: true });

export const OrganizationNode = z.strictObject({
  "@type": z.literal("Organization"),
  "@id": Iri,
  name: Text,
  url: Iri,
  logo: Iri,
  sameAs: z.array(Iri).min(1),
});

export const WebSiteNode = z.strictObject({
  "@type": z.literal("WebSite"),
  "@id": Iri,
  name: Text,
  url: Iri,
  publisher: Ref,
});

export const WebApiNode = z.strictObject({
  "@type": z.literal("WebAPI"),
  "@id": Iri,
  name: Text,
  documentation: Iri,
  provider: Ref,
});

const DataDownload = z.strictObject({
  "@type": z.literal("DataDownload"),
  encodingFormat: Text,
  contentUrl: Iri,
});

export const DatasetNode = z.strictObject({
  "@type": z.literal("Dataset"),
  "@id": Iri,
  name: Text,
  // Google's Dataset search asks for 50 to 5,000 characters.
  description: z.string().min(50).max(5000),
  url: Iri,
  license: Iri,
  creator: Ref,
  // Left out when the calendar covers every date (it closes on no holiday) or none yet.
  temporalCoverage: z
    .string()
    .regex(/^\d{4}-\d{2}-\d{2}\/\d{4}-\d{2}-\d{2}$/)
    .optional(),
  // Left out at revision 0, before anything was published.
  dateModified: IsoDateTime.optional(),
  version: Text,
  distribution: z.array(DataDownload).min(1),
  isBasedOn: z.array(Iri).min(1).optional(),
  variableMeasured: z.literal("business day status"),
});

const Answer = z.strictObject({ "@type": z.literal("Answer"), text: Text });

const Question = z.strictObject({
  "@type": z.literal("Question"),
  name: Text,
  acceptedAnswer: Answer,
});

export const QuestionNode = Question.extend({ "@id": Iri });

export const WebPageNode = z.strictObject({
  "@type": z.literal("WebPage"),
  "@id": Iri,
  url: Iri,
  name: Text,
  description: Text.optional(),
  isPartOf: Ref,
  breadcrumb: Ref.optional(),
  about: z.union([Ref, z.array(Ref).min(1)]).optional(),
  mainEntity: Ref.optional(),
  dateModified: IsoDateTime.optional(),
  citation: z.array(Iri).min(1).optional(),
});

export const FaqPageNode = z.strictObject({
  "@type": z.literal("FAQPage"),
  "@id": Iri,
  isPartOf: Ref,
  mainEntity: z.array(Question).min(1),
});

export const BreadcrumbListNode = z
  .strictObject({
    "@type": z.literal("BreadcrumbList"),
    "@id": Iri,
    itemListElement: z
      .array(
        z.strictObject({
          "@type": z.literal("ListItem"),
          position: z.number().int().min(1),
          name: Text,
          item: Iri,
        }),
      )
      .min(1),
  })
  .refine((list) => list.itemListElement.every((entry, index) => entry.position === index + 1), {
    message: "Breadcrumb positions count up from 1",
  });

const Node = z.discriminatedUnion("@type", [
  OrganizationNode,
  WebSiteNode,
  WebApiNode,
  DatasetNode,
  QuestionNode,
  WebPageNode,
  FaqPageNode,
  BreadcrumbListNode,
]);

export type GraphNode = z.infer<typeof Node>;

// A page may point at a calendar's Dataset without repeating it: the calendar page defines it.
const DEFINED_ELSEWHERE = /\/calendars\/[^/#]+#dataset$/;

export const Graph = z
  .strictObject({
    "@context": z.literal("https://schema.org"),
    "@graph": z.array(Node).min(1),
  })
  .superRefine((graph, context) => {
    const ids = graph["@graph"].map((node) => node["@id"]);
    const defined = new Set(ids);
    if (defined.size !== ids.length) {
      context.addIssue({ code: "custom", message: "Each @id is defined once" });
    }
    for (const ref of references(graph["@graph"])) {
      if (!defined.has(ref) && !DEFINED_ELSEWHERE.test(ref)) {
        context.addIssue({ code: "custom", message: `Nothing in the graph is ${ref}` });
      }
    }
  });

export type Graph = z.infer<typeof Graph>;

/** Every {"@id": …} reference below the top level of each node. */
function references(nodes: unknown[]): string[] {
  const found: string[] = [];
  const visit = (value: unknown, top: boolean): void => {
    if (Array.isArray(value)) {
      value.forEach((item) => visit(item, false));
    } else if (typeof value === "object" && value !== null) {
      const entries = Object.entries(value);
      if (!top && entries.length === 1 && entries[0]?.[0] === "@id") {
        found.push(String(entries[0][1]));
        return;
      }
      entries.forEach(([, item]) => visit(item, false));
    }
  };
  nodes.forEach((node) => visit(node, true));
  return found;
}
