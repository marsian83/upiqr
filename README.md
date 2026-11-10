# UPI QR

Some Indian apps (food delivery, ticketing, bill pay) only let you pay by
handing off to a UPI app installed on the same phone. They never show a QR
code. That's a problem if the account you want to pay from is on another
phone, or if you pay through something that only scans QR codes.

UPI QR registers itself as a UPI app on Android. When a merchant app hands it
a payment, it shows that exact payment as a QR code you can scan with any UPI
app on any device. The merchant gets paid exactly as if you'd used a normal
UPI app: same payee, same amount, same transaction reference.

## How it works

Merchant apps start a UPI payment by opening a `upi://pay?pa=…&am=…&tr=…`
link with whichever UPI app you pick. That link is the same thing a UPI QR
code contains, so UPI QR just draws it.

- **QR screen.** Merchant, payee address, amount and a scannable code, at full
  brightness. Long-press the code to copy the link. The ⋯ menu has copy,
  share, save to Photos, and "pay with another app" (forwards the request to
  a real UPI app and passes its result back).
- **Auto-confirm (Razorpay only).** If the payment came through Razorpay, the
  app checks Razorpay's public payment-status endpoint every 3 seconds. Once
  it shows paid, it returns to the merchant on its own.
- **Manual confirm.** For other payment gateways, tap **I've paid** *after*
  the money has gone through. Merchants only check for a short time after you
  return (Zomato: about 40 seconds), so going back too early means a failed
  payment.

## Install

Download `upiqr.apk` from Releases and install it. Android 10 or newer.

## Merchant apps that hide it

Many apps don't show every UPI app. They only list installed apps from their
own allowlist of known package names, so UPI QR won't appear. The build
script can install a copy under a different package name:

```sh
PKG=<package.name> ./build.sh <adb-serial>
```

If you use this, you're installing an app on your own phone under another
app's identity. Merchant apps may show that app's name and logo for it. It
can't be installed next to the real app, and it may break the merchant's
terms of service. **Don't distribute APKs built this way.** A payment APK
that pretends to be a real UPI app looks the same as malware, and nobody
receiving it can check what's inside.

## Build

Needs the Android SDK command-line tools (build-tools and one platform) and a
JDK. No Gradle.

```sh
./build.sh                      # → upiqr.apk, signed with a local debug key
./build.sh <adb-serial>         # build and install
UPIQR_KEYSTORE=… UPIQR_KEYSTORE_PASS=… ./build.sh   # release signing
```

## Privacy

- No accounts, analytics or trackers.
- The only network request is the Razorpay status check, and only for
  Razorpay payments. It sends the payment ID from the link to
  `api.razorpay.com`.
- The most recent payment link is stored in the app's private storage so the
  launcher screen can show it again.
- Payment links aren't logged. To debug, run
  `adb shell setprop log.tag.UPIQR DEBUG`.

## Limitations

- Android only. On iOS, merchant apps open specific payment apps directly,
  and there's no way to send a result back.
- Auto-confirm only covers Razorpay. Other gateways have no public status
  check.
- A merchant's payment request can expire while you're paying from another
  device. If that happens, the merchant will usually refund it automatically.
- This is a personal side project, not a payments product, and it has no
  tests. Check the amount and payee before you pay.

## License

MIT, see [LICENSE](LICENSE). Includes Project Nayuki's
[QR Code generator](https://github.com/nayuki/QR-Code-generator) (MIT), see
[NOTICE](NOTICE).
