"use client";

import { Tooltip } from "radix-ui";
import type { ReactElement, ReactNode } from "react";

/**
 * A hover and keyboard-focus hint for one element. The child must accept a ref and DOM props, as
 * a button or span does. Touch screens show no hover, so the hint never carries the only copy of
 * something: the element keeps its own label.
 */
export function Tip({
  label,
  children,
  side = "top",
}: {
  label: ReactNode;
  children: ReactElement;
  side?: "top" | "bottom" | "left" | "right";
}) {
  return (
    <Tooltip.Provider delayDuration={150} skipDelayDuration={300}>
      <Tooltip.Root>
        <Tooltip.Trigger asChild>{children}</Tooltip.Trigger>
        <Tooltip.Portal>
          <Tooltip.Content
            side={side}
            sideOffset={6}
            collisionPadding={8}
            className="z-50 max-w-[18rem] rounded-md bg-ink px-2.5 py-1.5 text-[13px] leading-[18px] text-white shadow-lg"
          >
            {label}
            <Tooltip.Arrow className="fill-ink" />
          </Tooltip.Content>
        </Tooltip.Portal>
      </Tooltip.Root>
    </Tooltip.Provider>
  );
}
