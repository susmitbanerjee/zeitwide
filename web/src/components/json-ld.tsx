import { serializeJsonLd } from "@/lib/seo";

/**
 * Structured data for search engines. This is the one place the app sets inner HTML: React would
 * entity-escape the JSON otherwise. serializeJsonLd escapes every character that could end the
 * script element, and `application/ld+json` is data the browser never executes.
 */
export function JsonLd({ data }: { data: unknown }) {
  return (
    <script
      type="application/ld+json"
      dangerouslySetInnerHTML={{ __html: serializeJsonLd(data) }}
    />
  );
}
