import type { Metadata } from "next";

import spec from "@/api/openapi.json";
import { JsonLd } from "@/components/json-ld";
import { docCrumbs } from "@/components/public/doc-page";
import { Breadcrumbs } from "@/components/site-chrome";
import { Card, Table, Td, Th } from "@/components/ui/primitives";
import { env } from "@/lib/env";
import { anchor, operations, schemaNames, typeLabel, type OpenApi } from "@/lib/openapi";
import { absoluteUrl } from "@/lib/seo";
import { contentGraph } from "@/lib/structured-data";

const api = spec as unknown as OpenApi;

export const metadata: Metadata = {
  title: "API reference",
  description:
    "Every Zeitwide API route, parameter, response and schema, generated from the OpenAPI contract.",
  alternates: { canonical: absoluteUrl(env.siteUrl, "/docs/api") },
};

/** C4: the API reference, generated at build time from api/openapi.yaml (via openapi.json). */
export default function ApiReferencePage() {
  const all = operations(api);
  const crumbs = docCrumbs("API reference");
  const tags = [...new Set(all.map((operation) => operation.tag))].filter(
    (tag) => tag !== "webhooks",
  );
  return (
    <div className="grid gap-8 lg:grid-cols-[220px_minmax(0,1fr)]">
      <nav aria-label="API reference" className="lg:sticky lg:top-4 lg:self-start">
        <ul className="flex flex-col text-sm">
          {tags.map((tag) => (
            <li key={tag}>
              <a
                href={`#tag-${tag}`}
                className="flex min-h-11 items-center font-semibold capitalize"
              >
                {tag.replace("-", " ")}
              </a>
            </li>
          ))}
          <li>
            <a href="#schemas" className="flex min-h-11 items-center font-semibold">
              Schemas
            </a>
          </li>
        </ul>
      </nav>
      <div className="flex min-w-0 flex-col gap-10">
        <header className="flex flex-col gap-2">
          <JsonLd
            data={contentGraph(
              {
                url: absoluteUrl(env.siteUrl, "/docs/api"),
                title: "API reference",
                crumbs,
                describesApi: { name: api.info.title },
              },
              env.siteUrl,
            )}
          />
          <Breadcrumbs items={crumbs} />
          <h1 className="text-4xl font-bold tracking-[-0.02em]">API reference</h1>
          <p className="text-ink-muted">
            {api.info.title} {api.info.version}. Generated from the OpenAPI contract, so it always
            matches the API.
          </p>
        </header>
        {tags.map((tag) => (
          <section
            key={tag}
            id={`tag-${tag}`}
            aria-labelledby={`tag-${tag}-title`}
            className="flex flex-col gap-4"
          >
            <h2 id={`tag-${tag}-title`} className="text-2xl font-semibold capitalize">
              {tag.replace("-", " ")}
            </h2>
            {all
              .filter((operation) => operation.tag === tag)
              .map((operation) => (
                <Card
                  key={operation.id}
                  id={anchor(operation.id)}
                  className="flex flex-col gap-3 p-5"
                >
                  <h3 className="flex flex-wrap items-baseline gap-2 font-mono text-[15px]">
                    <span className="rounded bg-ink px-2 py-0.5 text-xs font-semibold text-white">
                      {operation.method}
                    </span>
                    <span className="break-all">{operation.path}</span>
                  </h3>
                  <p className="font-semibold">{operation.summary}</p>
                  {operation.description !== undefined && (
                    <p className="text-sm whitespace-pre-line text-ink-muted">
                      {operation.description.trim()}
                    </p>
                  )}
                  <p className="text-sm text-ink-muted">
                    {operation.authenticated
                      ? "Needs an API key."
                      : "Open to anyone, no key needed."}
                  </p>
                  {operation.parameters.length > 0 && (
                    <Table>
                      <caption className="sr-only">Parameters for {operation.id}</caption>
                      <thead>
                        <tr>
                          <Th scope="col">Parameter</Th>
                          <Th scope="col">In</Th>
                          <Th scope="col">Type</Th>
                          <Th scope="col">Notes</Th>
                        </tr>
                      </thead>
                      <tbody>
                        {operation.parameters.map((parameter) => (
                          <tr key={`${parameter.in}-${parameter.name}`}>
                            <Td className="font-mono">
                              {parameter.name}
                              {parameter.required ? " *" : ""}
                            </Td>
                            <Td>{parameter.in}</Td>
                            <Td className="font-mono text-xs">{parameter.type}</Td>
                            <Td className="text-ink-muted">{parameter.description}</Td>
                          </tr>
                        ))}
                      </tbody>
                    </Table>
                  )}
                  {operation.body !== undefined && (
                    <p className="text-sm">
                      Body:{" "}
                      <a href={`#schema-${operation.body}`} className="font-mono">
                        {operation.body}
                      </a>
                    </p>
                  )}
                  <ul className="flex flex-col gap-1 text-sm">
                    {operation.responses.map((response) => (
                      <li key={response.status}>
                        <span className="font-mono font-semibold">{response.status}</span>{" "}
                        {response.description}
                        {response.type !== undefined && (
                          <>
                            {" "}
                            <span className="font-mono text-xs">{response.type}</span>
                          </>
                        )}
                      </li>
                    ))}
                  </ul>
                </Card>
              ))}
          </section>
        ))}
        <section id="schemas" aria-labelledby="schemas-title" className="flex flex-col gap-4">
          <h2 id="schemas-title" className="text-2xl font-semibold">
            Schemas
          </h2>
          {schemaNames(api).map((name) => {
            const schema = api.components.schemas[name];
            const properties = Object.entries(schema?.properties ?? {});
            return (
              <Card key={name} id={`schema-${name}`} className="flex flex-col gap-2 p-5">
                <h3 className="font-mono font-semibold">{name}</h3>
                {schema?.description !== undefined && (
                  <p className="text-sm whitespace-pre-line text-ink-muted">
                    {schema.description.trim()}
                  </p>
                )}
                {properties.length === 0 ? (
                  <p className="font-mono text-xs">{typeLabel(schema)}</p>
                ) : (
                  <Table>
                    <caption className="sr-only">Fields of {name}</caption>
                    <thead>
                      <tr>
                        <Th scope="col">Field</Th>
                        <Th scope="col">Type</Th>
                        <Th scope="col">Notes</Th>
                      </tr>
                    </thead>
                    <tbody>
                      {properties.map(([field, property]) => (
                        <tr key={field}>
                          <Td className="font-mono">
                            {field}
                            {schema?.required?.includes(field) ? " *" : ""}
                          </Td>
                          <Td className="font-mono text-xs">{typeLabel(property)}</Td>
                          <Td className="text-ink-muted">{property.description}</Td>
                        </tr>
                      ))}
                    </tbody>
                  </Table>
                )}
              </Card>
            );
          })}
        </section>
      </div>
    </div>
  );
}
