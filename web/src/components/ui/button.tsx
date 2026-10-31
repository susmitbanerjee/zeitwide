import { cva, type VariantProps } from "class-variance-authority";
import { Slot } from "radix-ui";
import type { ComponentProps } from "react";

import { cn } from "@/lib/cn";

// Every size keeps a 44 px touch target.
export const buttonVariants = cva(
  "inline-flex min-h-11 items-center justify-center gap-2 rounded-md px-4 text-[15px] font-semibold whitespace-nowrap no-underline transition-colors disabled:pointer-events-none disabled:opacity-60",
  {
    variants: {
      variant: {
        primary: "bg-ink text-white hover:bg-[#1d2a40] hover:text-white",
        accent: "bg-accent text-white hover:bg-accent-hover hover:text-white",
        outline:
          "border border-line-strong bg-surface text-ink hover:bg-surface-sunken hover:text-ink",
        ghost: "text-accent hover:bg-accent-soft hover:text-accent-hover",
        danger:
          "border border-closed bg-surface text-closed hover:bg-closed-soft hover:text-closed",
      },
      size: { default: "", sm: "px-3 text-sm", icon: "w-11 px-0" },
    },
    defaultVariants: { variant: "primary", size: "default" },
  },
);

export interface ButtonProps extends ComponentProps<"button">, VariantProps<typeof buttonVariants> {
  asChild?: boolean;
}

export function Button({ className, variant, size, asChild = false, ...props }: ButtonProps) {
  const Component = asChild ? Slot.Root : "button";
  return <Component className={cn(buttonVariants({ variant, size }), className)} {...props} />;
}
