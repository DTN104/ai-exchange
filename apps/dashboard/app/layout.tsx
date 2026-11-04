import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "AI Exchange · Project status",
  description: "An open-source market simulation project. Check local backend connectivity and development progress.",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body>{children}</body></html>;
}
