import type { Metadata } from "next";
import { headers } from "next/headers";
import { Geist, Geist_Mono } from "next/font/google";
import "./globals.css";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export async function generateMetadata(): Promise<Metadata> {
  const requestHeaders = await headers();
  const host = requestHeaders.get("x-forwarded-host") ?? requestHeaders.get("host") ?? "isdm-beta-control.openai.site";
  const protocol = requestHeaders.get("x-forwarded-proto") ?? "https";
  const image = `${protocol}://${host}/og.png`;
  return {
    title: "ISDM Companion Beta Control",
    description: "Private command desk for the ISDM Companion Android beta.",
    openGraph: {
      title: "ISDM Companion Beta Control",
      description: "Ten testers. Five teaching days. One safety control.",
      type: "website",
      images: [{ url: image, width: 1732, height: 908, alt: "ISDM Companion Beta Control" }],
    },
    twitter: {
      card: "summary_large_image",
      title: "ISDM Companion Beta Control",
      description: "Ten testers. Five teaching days. One safety control.",
      images: [image],
    },
  };
}

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="en">
      <body
        className={`${geistSans.variable} ${geistMono.variable} antialiased`}
      >
        {children}
      </body>
    </html>
  );
}
