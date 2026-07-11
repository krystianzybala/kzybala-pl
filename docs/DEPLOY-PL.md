# Publikacja na GitHub Pages krok po kroku

## 1. Utwórz repozytorium

Na GitHub:

1. Kliknij **New repository**.
2. Nazwa: `kzybala-pl`.
3. Visibility: **Public**.
4. Nie zaznaczaj generowania README, `.gitignore` ani licencji, ponieważ paczka już je zawiera.
5. Kliknij **Create repository**.

Publiczne repo jest najlepszym wyborem dla GitHub Pages w tym przypadku. Strona i tak jest publiczna, a repozytorium pokazuje porządny, prosty projekt techniczny. Ukrywanie HTML strony wizytówkowej w prywatnym repozytorium nie chroni żadnej tajemnicy, bo przeglądarka i tak pobiera cały kod.

## 2. Rozpakuj paczkę

```bash
unzip kzybala-pl.zip
cd kzybala-pl
```

## 3. Wyślij projekt

```bash
git init
git add .
git commit -m "Initial kzybala.pl website"
git branch -M main
git remote add origin git@github.com:TWÓJ_LOGIN/kzybala-pl.git
git push -u origin main
```

Możesz także wrzucić pliki przez interfejs GitHub, ale Git istnieje właśnie po to, aby nie przeciągać katalogów myszką jak w 2004 roku.

## 4. Włącz GitHub Pages

1. Wejdź do repozytorium.
2. **Settings → Pages**.
3. W sekcji **Build and deployment**:
   - Source: **GitHub Actions**.
4. Wejdź w zakładkę **Actions** i poczekaj, aż workflow będzie zielony.

## 5. Skonfiguruj domenę w GitHub

W **Settings → Pages**:

```text
Custom domain: kzybala.pl
```

Zapisz.

## 6. Skonfiguruj DNS w home.pl

Dodaj rekordy A dla domeny głównej:

```text
@  A  185.199.108.153
@  A  185.199.109.153
@  A  185.199.110.153
@  A  185.199.111.153
```

Dodaj rekord:

```text
www  CNAME  TWÓJ_LOGIN.github.io
```

Usuń kolidujące rekordy `A`, `AAAA` albo przekierowania dla `@` i `www`.

## 7. HTTPS

Po propagacji DNS GitHub wystawi certyfikat automatycznie. Wróć do:

```text
Settings → Pages
```

i zaznacz:

```text
Enforce HTTPS
```

Nie kupuj osobnego SSL w home.pl.

## 8. Podgląd lokalny

```bash
python3 -m http.server 8080
```

Następnie:

```text
http://localhost:8080
```
