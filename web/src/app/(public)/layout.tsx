import { PublicShell } from "@/components/site-chrome";
import { WarmUp } from "@/components/warm-up";

export default function PublicLayout({ children }: LayoutProps<"/">) {
  return (
    <PublicShell>
      <WarmUp />
      {children}
    </PublicShell>
  );
}
