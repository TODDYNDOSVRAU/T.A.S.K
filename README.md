<p align="center">
  <img src="branding/task-logo-negativo.svg" alt="T.A.S.K. Logo" width="108" height="108" />
</p>

# T.A.S.K. — Totally Accurate Smart Keyboard

Teclado Android **100% offline** desenvolvido pela **T.A.L. (Totally Accurate Labs)**, focado em zero permissão de rede, baixa latência de desenho/toque e motor **Dynamic Tap Zones 2D** com aprendizado direcional por tecla.

- **Privacidade estrita:** sem `INTERNET`, `READ_CONTACTS` ou `REQUEST_INSTALL_PACKAGES`. Toda adaptação numérica (viés por fileira, centróide por tecla, matriz de confusão e hashes FNV-1a de escudo contra autocorreção) permanece apenas no dispositivo.
- **Idiomas integrados:** `PT-BR` (`169k` palavras) e `EN-US` (`129k` palavras) em Trie CSR compacto (`Flat-Int`) e decodificador glide offline (**T.A.S.K. SHARK²**).
- **Arquitetura em 3 camadas:** `ESSENTIAL`, `ADVANCED` e `ENGINEERING` no padrão **T.A.L. Grid 2x**.

## Download e Atualização pelo GitHub

Como o T.A.S.K. não solicita permissão `INTERNET`, ele nunca baixa pacotes em segundo plano:

1. **Download direto:** baixe `release.apk` na página [GitHub Releases](https://github.com/TODDYNDOSVRAU/T.A.S.K/releases/latest) e confira o checksum no arquivo `release.apk.sha256`.
2. **Auto-update opcional via Obtainium:** importe o repositório [`https://github.com/TODDYNDOSVRAU/T.A.S.K`](https://github.com/TODDYNDOSVRAU/T.A.S.K) ou use [`obtainium.json`](obtainium.json) no [Obtainium](https://obtainium.imranr.dev/) para receber avisos de nova release do GitHub com verificação pela mesma chave de assinatura.

## Verificação de Assinatura e Play Protect

Todo APK oficial publicado aqui é alinhado com `zipalign -p 4` e assinado com v1/v2/v3 usando a mesma chave (`CN=T.A.S.K., O=Totally Accurate Labs, C=BR`):

- **Certificado SHA-256:** `c8ccda66f36971aa42ee2e8485b592120b41c272caf00e304be37dfe5cb1e0b5`
- **Sobre o aviso do Google Play Protect:** APKs distribuídos fora da Google Play Store podem exibir o diálogo *"App desconhecido"* / verificação do Play Protect na primeira instalação enquanto o certificado não estiver registrado em uma conta Google Play Console verificada. Não existe flag local no APK que remova esse aviso; confira o SHA-256 do arquivo (`release.apk.sha256`) e do certificado acima antes de confirmar *"Instalar assim mesmo"*.

---

Developed by Totally Accurate Labs.  
© 2026 Totally Accurate Labs · MADE BY T.A.L.
