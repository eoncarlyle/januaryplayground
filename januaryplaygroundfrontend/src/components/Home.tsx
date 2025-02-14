import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableFooter,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useEffect, useState } from "react";
import { useLocation } from "wouter";

import { AuthProps } from "../model";
import { setupWebsocket, useAuthRedirect } from "../util/rest";
import AuthNavBar from "./AuthNavBar";
import Layout from "./Layout";

export default function Home(authProps: AuthProps) {
  const [location, setLocation] = useLocation();

  const [socketState, setSocketState] = useState<null | WebSocket>(null);
  const [socketMessageState, setSocketMessageState] = useState("");
  useEffect(() => {
    if (socketState) return;
    const socket = new WebSocket("ws://localhost:7070/ws");
    setSocketState(socket);
    setupWebsocket(
      //TODO fix, is ugly
      authProps.authState.email || "",
      socket,
      setSocketState,
      setSocketMessageState,
    );

    return () => {
      if (socket.readyState === WebSocket.OPEN) {
        socket.close();
      }
    };
  }, [socketState, authProps.authState]);
  // Check auth if we know it is wrong
  useAuthRedirect(true, authProps, location, setLocation);

  const invoices = [
    {
      invoice: "INV001",
      paymentStatus: "Paid",
      totalAmount: "$250.00",
      paymentMethod: "Credit Card",
    },
    {
      invoice: "INV002",
      paymentStatus: "Pending",
      totalAmount: "$150.00",
      paymentMethod: "PayPal",
    },
    {
      invoice: "INV003",
      paymentStatus: "Unpaid",
      totalAmount: "$350.00",
      paymentMethod: "Bank Transfer",
    },
    {
      invoice: "INV004",
      paymentStatus: "Paid",
      totalAmount: "$450.00",
      paymentMethod: "Credit Card",
    },
    {
      invoice: "INV005",
      paymentStatus: "Paid",
      totalAmount: "$550.00",
      paymentMethod: "PayPal",
    },
    {
      invoice: "INV006",
      paymentStatus: "Pending",
      totalAmount: "$200.00",
      paymentMethod: "Bank Transfer",
    },
    {
      invoice: "INV007",
      paymentStatus: "Unpaid",
      totalAmount: "$300.00",
      paymentMethod: "Credit Card",
    },
  ];

  return (
    <Layout>
      <>
        <AuthNavBar
          authState={authProps.authState}
          setAuth={authProps.setAuth}
          persistentAuthState={authProps.persistentAuthState}
          setPersistentAuth={authProps.setPersistentAuth}
        />
        <div className="p-4">
          <h1 className="text-xl font-bold"> Market </h1>
          <Table className="w-3/5 justify-center items-center mx-auto my-3">
            <TableCaption>Useful table caption</TableCaption>
            <TableHeader>
              <TableRow>
                <TableHead className="">Column 1</TableHead>
                <TableHead>Column 2</TableHead>
                <TableHead className="">Column 3</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {invoices.map((invoice) => (
                <TableRow key={invoice.invoice}>
                  <TableCell className="font-medium">
                    {invoice.invoice}
                  </TableCell>
                  <TableCell>{invoice.paymentStatus}</TableCell>
                  <TableCell className="">{invoice.totalAmount}</TableCell>
                </TableRow>
              ))}
            </TableBody>
            {/*<TableFooter>
              <TableRow>
                <TableCell colSpan={2}>Total</TableCell>
                <TableCell className="">$2,500.00</TableCell>
              </TableRow>
              </TableFooter */}
          </Table>
        </div>
      </>
    </Layout>
  );
}
