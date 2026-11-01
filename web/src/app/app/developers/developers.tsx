"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useId, useState } from "react";

import { unwrap, type ChangeType, type CreatedApiKey, type CreatedWebhook } from "@/api/client";
import { PageHeading } from "@/components/app-chrome";
import { CopyButton } from "@/components/copy-button";
import { Button } from "@/components/ui/button";
import {
  Card,
  ErrorText,
  Input,
  Label,
  SectionTitle,
  Status,
  Table,
  Td,
  Th,
} from "@/components/ui/primitives";
import { browserApi, idempotencyKey } from "@/lib/api-browser";
import { formatInstant } from "@/lib/format";
import { errorMessage } from "@/lib/queries";

const EVENTS: { value: ChangeType; label: string }[] = [
  { value: "closure_added", label: "closure_added" },
  { value: "closure_removed", label: "closure_removed" },
  { value: "closure_changed", label: "closure_changed" },
  { value: "basis_changed", label: "basis_changed" },
];

/** C1: API keys and webhooks. Secrets are shown once, straight from the create response. */
export function Developers() {
  return (
    <div className="flex max-w-5xl flex-col gap-10">
      <PageHeading
        title="Keys and webhooks"
        lead="10,000 calls a day per key, free in the beta. We keep a hash of each key, never the key."
      />
      <ApiKeys />
      <Webhooks />
    </div>
  );
}

function OneTimeSecret({
  label,
  value,
  onDone,
}: {
  label: string;
  value: string;
  onDone: () => void;
}) {
  return (
    <div
      role="status"
      className="flex flex-col gap-3 rounded-md border border-warning bg-warning-soft p-4"
    >
      <p className="font-semibold">{label}</p>
      <p className="font-mono text-sm break-all">{value}</p>
      <p className="text-sm">
        This is the only time you&apos;ll see it. If you lose it, make a new one.
      </p>
      <div className="flex gap-2">
        <CopyButton text={value} />
        <Button variant="ghost" onClick={onDone}>
          I&apos;ve saved it
        </Button>
      </div>
    </div>
  );
}

function ApiKeys() {
  const client = useQueryClient();
  const id = useId();
  const [name, setName] = useState("");
  const [created, setCreated] = useState<CreatedApiKey | null>(null);
  const keys = useQuery({
    queryKey: ["api-keys"],
    queryFn: async () => unwrap(await browserApi.GET("/v1/account/api-keys")),
  });
  const create = useMutation({
    mutationFn: async () =>
      unwrap(
        await browserApi.POST("/v1/account/api-keys", {
          body: { name },
          params: { header: { "Idempotency-Key": idempotencyKey() } },
        }),
      ),
    onSuccess: async (key) => {
      setCreated(key);
      setName("");
      await client.invalidateQueries({ queryKey: ["api-keys"] });
    },
  });
  const revoke = useMutation({
    mutationFn: async (keyId: string) => {
      await browserApi.DELETE("/v1/account/api-keys/{id}", { params: { path: { id: keyId } } });
    },
    onSuccess: () => client.invalidateQueries({ queryKey: ["api-keys"] }),
  });
  return (
    <section aria-labelledby="keys" className="flex flex-col gap-4">
      <SectionTitle id="keys">API keys</SectionTitle>
      <Card className="flex flex-col gap-4 p-5">
        <form
          className="flex flex-wrap items-end gap-3"
          onSubmit={(event) => {
            event.preventDefault();
            create.mutate();
          }}
        >
          <div className="flex min-w-60 flex-1 flex-col gap-1">
            <Label htmlFor={`${id}-name`}>Name this key</Label>
            <Input
              id={`${id}-name`}
              required
              maxLength={100}
              placeholder="payments-service"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
          <Button type="submit" disabled={create.isPending}>
            Create key
          </Button>
        </form>
        {create.isError && <ErrorText>{errorMessage(create.error)}</ErrorText>}
        {created !== null && (
          <OneTimeSecret
            label={`Your key for ${created.name}`}
            value={created.key}
            onDone={() => setCreated(null)}
          />
        )}
      </Card>
      {keys.isPending && <Status>Loading keys…</Status>}
      {keys.isError && <ErrorText>{errorMessage(keys.error)}</ErrorText>}
      {keys.data !== undefined && keys.data.length > 0 && (
        <Card>
          <Table>
            <thead>
              <tr>
                <Th scope="col">Name</Th>
                <Th scope="col">Key</Th>
                <Th scope="col">Created</Th>
                <Th scope="col">Last used</Th>
                <Th scope="col">
                  <span className="sr-only">Actions</span>
                </Th>
              </tr>
            </thead>
            <tbody>
              {keys.data.map((key) => (
                <tr key={key.id}>
                  <Td className="font-semibold">{key.name}</Td>
                  <Td className="font-mono">{key.prefix}_…</Td>
                  <Td>{formatInstant(key.created_at)}</Td>
                  <Td>{formatInstant(key.last_used_at)}</Td>
                  <Td>
                    {key.revoked_at === null || key.revoked_at === undefined ? (
                      <Button
                        size="sm"
                        variant="danger"
                        aria-label={`Revoke ${key.name}`}
                        tip="Stop this key working at once"
                        onClick={() => {
                          if (
                            window.confirm(`Revoke ${key.name}? Calls with it will fail at once.`)
                          ) {
                            revoke.mutate(key.id);
                          }
                        }}
                      >
                        Revoke
                      </Button>
                    ) : (
                      <span className="text-ink-muted">
                        Revoked {formatInstant(key.revoked_at)}
                      </span>
                    )}
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        </Card>
      )}
    </section>
  );
}

function Webhooks() {
  const client = useQueryClient();
  const id = useId();
  const [url, setUrl] = useState("");
  const [events, setEvents] = useState<ChangeType[]>([
    "closure_added",
    "closure_removed",
    "closure_changed",
  ]);
  const [created, setCreated] = useState<CreatedWebhook | null>(null);
  const [tested, setTested] = useState<string | null>(null);
  const hooks = useQuery({
    queryKey: ["webhooks"],
    queryFn: async () => unwrap(await browserApi.GET("/v1/account/webhooks")),
  });
  const create = useMutation({
    mutationFn: async () =>
      unwrap(
        await browserApi.POST("/v1/account/webhooks", {
          body: { url, events },
          params: { header: { "Idempotency-Key": idempotencyKey() } },
        }),
      ),
    onSuccess: async (hook) => {
      setCreated(hook);
      setUrl("");
      await client.invalidateQueries({ queryKey: ["webhooks"] });
    },
  });
  const test = useMutation({
    mutationFn: async (hookId: string) =>
      unwrap(
        await browserApi.POST("/v1/account/webhooks/{id}/test", {
          params: { path: { id: hookId } },
        }),
      ),
    onSuccess: (result) =>
      setTested(
        result.succeeded
          ? `Test delivered (HTTP ${result.response_status})`
          : "Test delivery failed",
      ),
  });
  const remove = useMutation({
    mutationFn: async (hookId: string) => {
      await browserApi.DELETE("/v1/account/webhooks/{id}", { params: { path: { id: hookId } } });
    },
    onSuccess: () => client.invalidateQueries({ queryKey: ["webhooks"] }),
  });
  return (
    <section aria-labelledby="webhooks" className="flex flex-col gap-4">
      <SectionTitle id="webhooks">Webhooks</SectionTitle>
      <p className="text-sm text-ink-muted">
        Signed with HMAC-SHA256 in the <span className="font-mono">Zeitwide-Signature</span> header.
        HTTPS on a public host only.
      </p>
      <Card className="flex flex-col gap-4 p-5">
        <form
          className="flex flex-col gap-3"
          onSubmit={(event) => {
            event.preventDefault();
            create.mutate();
          }}
        >
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${id}-url`}>Endpoint URL</Label>
            <Input
              id={`${id}-url`}
              type="url"
              required
              pattern="https://.*"
              placeholder="https://ops.example.com/hooks/zeitwide"
              value={url}
              onChange={(e) => setUrl(e.target.value)}
            />
          </div>
          <fieldset className="flex flex-wrap gap-x-4">
            <legend className="mb-1 text-sm font-semibold">Events</legend>
            {EVENTS.map((event) => (
              <label
                key={event.value}
                className="inline-flex min-h-11 items-center gap-2 font-mono text-sm"
              >
                <input
                  type="checkbox"
                  className="size-4 accent-[#2a3fcf]"
                  checked={events.includes(event.value)}
                  onChange={(e) =>
                    setEvents(
                      e.target.checked
                        ? [...events, event.value]
                        : events.filter((v) => v !== event.value),
                    )
                  }
                />
                {event.label}
              </label>
            ))}
          </fieldset>
          <div>
            <Button type="submit" disabled={events.length === 0 || create.isPending}>
              Add webhook
            </Button>
          </div>
        </form>
        {create.isError && <ErrorText>{errorMessage(create.error)}</ErrorText>}
        {created !== null && (
          <OneTimeSecret
            label={`Signing secret for ${created.url}`}
            value={created.secret}
            onDone={() => setCreated(null)}
          />
        )}
      </Card>
      {tested !== null && <Status>{tested}</Status>}
      {hooks.isError && <ErrorText>{errorMessage(hooks.error)}</ErrorText>}
      <ul className="flex flex-col gap-2">
        {(hooks.data ?? []).map((hook) => (
          <li key={hook.id}>
            <Card className="flex flex-wrap items-center gap-3 p-4">
              <div className="flex min-w-0 flex-1 flex-col">
                <span className="font-mono text-sm break-all">{hook.url}</span>
                <span className="text-sm text-ink-muted">{hook.events.join(", ")}</span>
              </div>
              <Button
                size="sm"
                variant="outline"
                onClick={() => test.mutate(hook.id)}
                aria-label={`Send a test event to ${hook.url}`}
                tip="Send a signed sample event to this endpoint"
              >
                Send test
              </Button>
              <Button
                size="sm"
                variant="danger"
                onClick={() => remove.mutate(hook.id)}
                aria-label={`Delete ${hook.url}`}
                tip="Stop sending events to this endpoint"
              >
                Delete
              </Button>
            </Card>
          </li>
        ))}
      </ul>
    </section>
  );
}
