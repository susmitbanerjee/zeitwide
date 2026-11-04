// The two packages ship no types; only what the structured-data spec uses is declared.

declare module "@marbec/web-auto-extractor" {
  export default class WebAutoExtractor {
    constructor(options: { addLocation?: boolean; embedSource?: string[] });
    parse(html: string): unknown;
  }
}

declare module "@adobe/structured-data-validator" {
  export interface ValidationIssue {
    issueMessage: string;
    severity: "ERROR" | "WARNING";
    path?: unknown[];
    fieldNames?: string[];
  }
  export default class Validator {
    constructor(schemaOrg: unknown);
    validate(extracted: unknown): Promise<ValidationIssue[]>;
  }
}
