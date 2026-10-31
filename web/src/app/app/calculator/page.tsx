import type { Metadata } from "next";

import { Calculator } from "./calculator";

export const metadata: Metadata = { title: "Calculator" };

export default function CalculatorPage() {
  return <Calculator />;
}
