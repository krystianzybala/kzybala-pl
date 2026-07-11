# kzybala.pl

Static personal engineering site for Krystian Zybała.

## Stack

- plain HTML
- plain CSS
- minimal vanilla JavaScript
- GitHub Pages
- custom domain: `kzybala.pl`

No build step is required.

## Local preview

From the repository root:

```bash
python3 -m http.server 8080
```

Open:

```text
http://localhost:8080
```

Do not open `index.html` directly from the filesystem because root-relative links are intended to behave like a real website.

## GitHub Pages deployment

1. Create a **public** repository, for example `kzybala-pl`.
2. Upload all files from this directory to the repository root.
3. Push to the `main` branch.
4. Open **Settings → Pages**.
5. Under **Build and deployment**, choose **GitHub Actions**.
6. Wait for the `Deploy static site to GitHub Pages` workflow to complete.
7. In **Settings → Pages → Custom domain**, enter `kzybala.pl`.
8. After DNS validation, enable **Enforce HTTPS**.

## DNS for home.pl

Configure the apex domain:

```text
@   A   185.199.108.153
@   A   185.199.109.153
@   A   185.199.110.153
@   A   185.199.111.153
```

Configure `www`:

```text
www   CNAME   <YOUR-GITHUB-USERNAME>.github.io
```

Replace `<YOUR-GITHUB-USERNAME>` with the actual GitHub username.

The repository contains a `CNAME` file with:

```text
kzybala.pl
```

## Updating the CV

Replace:

```text
assets/cv/Krystian_Zybala_CV.pdf
```

Keep the same filename so existing links continue to work.

## Content safety

The case studies are deliberately sanitised. Review all public numbers and wording against contractual confidentiality obligations before publishing.
