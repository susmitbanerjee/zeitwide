/**
 * A typed view of api/openapi.yaml for the generated API reference. Only the parts the reference
 * renders; everything comes from the spec, nothing is written by hand.
 */

export interface SchemaObject {
  $ref?: string;
  type?: string | string[];
  format?: string;
  description?: string;
  enum?: unknown[];
  items?: SchemaObject;
  properties?: Record<string, SchemaObject>;
  required?: string[];
  oneOf?: SchemaObject[];
  allOf?: SchemaObject[];
  minimum?: number;
  maximum?: number;
  maxLength?: number;
  default?: unknown;
}

interface Parameter {
  $ref?: string;
  name?: string;
  in?: string;
  required?: boolean;
  description?: string;
  schema?: SchemaObject;
}

interface Operation {
  operationId: string;
  tags?: string[];
  summary?: string;
  description?: string;
  parameters?: Parameter[];
  security?: Record<string, unknown>[];
  requestBody?: { content?: Record<string, { schema?: SchemaObject }> };
  responses?: Record<
    string,
    { $ref?: string; description?: string; content?: Record<string, { schema?: SchemaObject }> }
  >;
}

export interface OpenApi {
  info: { title: string; version: string; description?: string };
  tags?: { name: string; description?: string }[];
  paths: Record<string, Record<string, Operation>>;
  components: {
    schemas: Record<string, SchemaObject>;
    parameters?: Record<string, Parameter>;
    responses?: Record<string, { description?: string }>;
  };
}

export interface OperationView {
  id: string;
  method: string;
  path: string;
  tag: string;
  summary: string;
  description: string | undefined;
  authenticated: boolean;
  parameters: {
    name: string;
    in: string;
    required: boolean;
    type: string;
    description: string | undefined;
  }[];
  body: string | undefined;
  responses: { status: string; description: string; type: string | undefined }[];
}

const METHODS = ["get", "post", "put", "delete"] as const;

export function refName(ref: string): string {
  return ref.split("/").at(-1) ?? ref;
}

/** A short type label: `Calendar`, `array of CalendarId`, `string (date)`. */
export function typeLabel(schema: SchemaObject | undefined): string {
  if (schema === undefined) {
    return "any";
  }
  if (schema.$ref !== undefined) {
    return refName(schema.$ref);
  }
  if (schema.oneOf !== undefined) {
    return schema.oneOf.map(typeLabel).join(" or ");
  }
  if (schema.allOf !== undefined) {
    return schema.allOf.map(typeLabel).join(" and ");
  }
  const type = Array.isArray(schema.type) ? schema.type.join(" or ") : (schema.type ?? "object");
  if (schema.items !== undefined) {
    return `array of ${typeLabel(schema.items)}`;
  }
  if (schema.enum !== undefined) {
    return schema.enum.map(String).join(" | ");
  }
  return schema.format === undefined ? type : `${type} (${schema.format})`;
}

export function operations(spec: OpenApi): OperationView[] {
  return Object.entries(spec.paths).flatMap(([path, item]) =>
    METHODS.filter((method) => item[method] !== undefined).map((method) => {
      const operation = item[method] as Operation;
      const json = operation.requestBody?.content?.["application/json"]?.schema;
      return {
        id: operation.operationId,
        method: method.toUpperCase(),
        path,
        tag: operation.tags?.[0] ?? "other",
        summary: operation.summary ?? operation.operationId,
        description: operation.description,
        authenticated: needsIdentity(operation.security ?? defaultSecurity(spec)),
        parameters: (operation.parameters ?? []).map((parameter) => {
          const resolved =
            parameter.$ref === undefined
              ? parameter
              : (spec.components.parameters?.[refName(parameter.$ref)] ?? parameter);
          return {
            name: resolved.name ?? "",
            in: resolved.in ?? "",
            required: resolved.required === true,
            type: typeLabel(resolved.schema),
            description: resolved.description,
          };
        }),
        body: json === undefined ? undefined : typeLabel(json),
        responses: Object.entries(operation.responses ?? {}).map(([status, response]) => {
          const resolved =
            response.$ref === undefined
              ? response
              : (spec.components.responses?.[refName(response.$ref)] ?? response);
          const schema = response.content?.["application/json"]?.schema;
          return {
            status,
            description: resolved.description ?? "",
            type: schema === undefined ? undefined : typeLabel(schema),
          };
        }),
      };
    }),
  );
}

/** An empty list (`security: []`) or an empty requirement (`{}`) means anyone may call it. */
function needsIdentity(security: Record<string, unknown>[]): boolean {
  return security.length > 0 && security.every((s) => Object.keys(s).length > 0);
}

function defaultSecurity(spec: OpenApi): Record<string, unknown>[] {
  return (spec as { security?: Record<string, unknown>[] }).security ?? [{}];
}

/** Schemas an operation list mentions, in name order, for the schema section. */
export function schemaNames(spec: OpenApi): string[] {
  return Object.keys(spec.components.schemas).sort((a, b) => a.localeCompare(b));
}

export function anchor(id: string): string {
  return id.replace(/[^A-Za-z0-9_-]/g, "-");
}
