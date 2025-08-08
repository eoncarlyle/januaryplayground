import {
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useAuth } from "@/util/queries.ts";
import { useEffect, useState } from "react";
import { useLocation } from "wouter";

import { setupWebsocket, useAuthRedirect } from "../util/rest";
import AuthNavBar from "./AuthNavBar";
import Layout from "./Layout";
import {Spinner} from "@/components/ui/spinnersx";

export default function Home() {
  const [location, setLocation] = useLocation();

  const { data: authData, status } = useAuth();
  const [socketState, setSocketState] = useState<null | WebSocket>(null);
  const [_, setSocketMessageState] = useState("");


  useEffect(() => {
    if (socketState) return;
    const socket = new WebSocket("ws://localhost:7070/ws");
    setSocketState(socket);
    setupWebsocket(
      //TODO fix, is ugly
      authData?.email || "",
      socket,
      setSocketState,
      setSocketMessageState,
    );

    return () => {
      if (socket.readyState === WebSocket.OPEN) {
        socket.close();
      }
    };
  }, [socketState, authData]);
  // Check auth if we know it is wrong
  useAuthRedirect(true, authData, location, setLocation);
  console.log(authData)
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

  if (status === "pending") {
    return <Spinner  size={64} />;
  } else if (status === "error") {
    return (<Layout>
      <p>"Something has gone wrong"</p>
    </Layout>)
  }

  return (
    <Layout>
      <>
        <AuthNavBar />
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
