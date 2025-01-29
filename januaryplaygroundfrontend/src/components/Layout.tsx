export default function Layout(props: { children: JSX.Element }) {
  return (
    <div className="flex flex-col min-h-screen w-screen">
      <main className="flex-1">{props.children}</main>
    </div>
  );
}
