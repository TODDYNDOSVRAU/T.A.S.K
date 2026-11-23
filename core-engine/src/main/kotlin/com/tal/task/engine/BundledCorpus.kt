package com.tal.task.engine

/**
 * Offline bundled PT-BR and EN-US frequency corpora + word bigrams.
 * Zero network dependency (D4).
 */
object BundledCorpus {

    fun createDictionary(language: Language): TrieDictionary = when (language) {
        Language.PT_BR -> buildPtBr()
        Language.EN_US -> buildEnUs()
    }

    private fun buildPtBr(): TrieDictionary {
        val dict = TrieDictionary(Language.PT_BR)
        ptBrWords.forEachIndexed { idx, word ->
            // Zipf-like descending frequency curve
            val freq = (10_000 / (idx + 5)).coerceAtLeast(15)
            dict.insertWord(word, freq)
        }
        ptBrBigrams.forEach { (a, b, c) -> dict.insertBigram(a, b, c) }
        return dict
    }

    private fun buildEnUs(): TrieDictionary {
        val dict = TrieDictionary(Language.EN_US)
        enUsWords.forEachIndexed { idx, word ->
            val freq = (10_000 / (idx + 5)).coerceAtLeast(15)
            dict.insertWord(word, freq)
        }
        enUsBigrams.forEach { (a, b, c) -> dict.insertBigram(a, b, c) }
        return dict
    }

    private val ptBrWords = listOf(
        "que", "não", "para", "uma", "com", "por", "mais", "como", "mas", "foi",
        "você", "muito", "está", "isso", "também", "quando", "seu", "sua", "sobre", "tudo",
        "pode", "fazer", "agora", "então", "aqui", "onde", "tempo", "assim", "bem", "dia",
        "bom", "boa", "tarde", "noite", "olá", "oi", "sim", "tudo", "certo", "obrigado",
        "obrigada", "valeu", "beleza", "vamos", "estou", "estava", "tenho", "tem", "tinha", "ter",
        "ser", "são", "era", "foram", "seja", "fosse", "vai", "vou", "vamos", "ir",
        "casa", "trabalho", "projeto", "sistema", "teclado", "tela", "texto", "palavra", "letra", "tecla",
        "predição", "precisão", "toque", "área", "modelo", "teste", "código", "app", "aplicativo", "celular",
        "android", "iphone", "tempo", "real", "rápido", "lento", "melhor", "maior", "menor", "novo",
        "nova", "primeiro", "primeira", "segundo", "segunda", "terceiro", "último", "próximo", "antes", "depois",
        "sempre", "nunca", "ainda", "hoje", "ontem", "amanhã", "semana", "mês", "ano", "hora",
        "minuto", "segundo", "vida", "pessoa", "pessoas", "gente", "amigo", "amiga", "família", "mundo",
        "brasil", "cidade", "lugar", "parte", "forma", "jeito", "modo", "caso", "coisa", "coisas",
        "exemplo", "ideia", "plano", "meta", "objetivo", "resultado", "dados", "informação", "configuração", "opção",
        "opções", "botão", "barra", "lista", "menu", "cor", "cores", "tema", "escuro", "claro",
        "fonte", "tamanho", "margem", "grade", "layout", "design", "interface", "usuário", "privacidade", "segurança",
        "senha", "offline", "local", "arquivo", "pasta", "versão", "build", "erro", "erros", "correção",
        "sugestão", "sugestões", "dicionário", "idioma", "português", "inglês", "espaço", "ponto", "vírgula", "acento",
        "acentos", "número", "números", "símbolo", "símbolos", "emoji", "emojis", "copiar", "colar", "histórico",
        "falar", "dizer", "ver", "olhar", "ouvir", "pensar", "saber", "querer", "poder", "dever",
        "precisar", "gostar", "usar", "criar", "abrir", "fechar", "salvar", "apagar", "limpar", "buscar",
        "encontrar", "mostrar", "esconder", "ativar", "desativar", "mudar", "trocar", "escolher", "selecionar", "enviar",
        "receber", "chegar", "sair", "entrar", "voltar", "continuar", "parar", "começar", "terminar", "acabar",
        "funcionar", "funciona", "funcionando", "rodar", "rodando", "testar", "testando", "medir", "calcular", "ajustar",
        "digitar", "digitando", "escrever", "escrevendo", "ler", "lendo", "responder", "perguntar", "explicar", "entender",
        "porque", "porquê", "portanto", "porém", "contudo", "todavia", "embora", "enquanto", "durante", "através",
        "entre", "contra", "desde", "até", "após", "sem", "sob", "sobre", "perante", "mediante",
        "cada", "outro", "outra", "outros", "outras", "mesmo", "mesma", "próprio", "própria", "algum",
        "alguma", "nenhum", "nenhuma", "todo", "toda", "todos", "todas", "qual", "quais", "quem",
        "quanto", "quanta", "quantos", "quantas", "cujo", "cuja", "aquele", "aquela", "este", "esta",
        "esse", "essa", "isto", "aquilo", "meu", "minha", "meus", "minhas", "nosso", "nossa",
        "dele", "dela", "deles", "delas", "comigo", "contigo", "consigo", "conosco", "nada", "ninguém",
        "alguém", "algo", "bastante", "demais", "apenas", "somente", "quase", "talvez", "realmente", "exatamente",
        "direto", "simples", "limpo", "claro", "preciso", "honesto", "calmo", "completo", "total", "real",
        "fácil", "difícil", "possível", "impossível", "importante", "necessário", "útil", "prático", "eficiente", "estável",
        "rápida", "lenta", "grande", "pequeno", "pequena", "alto", "alta", "baixo", "baixa", "forte",
        "fraco", "suave", "padrão", "agressivo", "dinâmico", "estático", "visual", "tátil", "sonoro", "automático",
        "manual", "ativo", "inativo", "pronto", "pendente", "concluído", "aprovado", "rejeitado", "correto", "incorreto",
        "ação", "solução", "decisão", "revisão", "atenção", "função", "conexão", "proteção", "direção", "posição",
        "questão", "razão", "visão", "missão", "paixão", "coração", "mão", "mãos", "pão", "chão",
        "avião", "estação", "situação", "condição", "produção", "construção", "educação", "comunicação", "aplicação", "execução",
        "verificação", "validação", "medição", "otimização", "integração", "arquitetura", "engenharia", "laboratório", "produto", "produtos",
        "latência", "quadro", "quadros", "memória", "processador", "bateria", "energia", "rede", "internet", "servidor",
        "banco", "tabela", "índice", "chave", "valor", "lista", "mapa", "bloco", "linha", "coluna",
        "página", "seção", "título", "subtítulo", "rodapé", "cabeçalho", "ícone", "imagem", "vídeo", "áudio",
        "música", "som", "volume", "brilho", "contraste", "sombra", "borda", "canto", "raio", "curva",
        "café", "água", "comida", "almoço", "jantar", "carro", "ônibus", "metrô", "rua", "avenida",
        "praia", "parque", "escola", "faculdade", "empresa", "escritório", "reunião", "mensagem", "ligação", "email"
    )

    private val ptBrBigrams = listOf(
        Triple("bom", "dia", 400),
        Triple("boa", "tarde", 380),
        Triple("boa", "noite", 420),
        Triple("tudo", "bem", 450),
        Triple("tudo", "certo", 350),
        Triple("muito", "bom", 320),
        Triple("muito", "obrigado", 340),
        Triple("eu", "estou", 300),
        Triple("eu", "acho", 260),
        Triple("eu", "quero", 250),
        Triple("não", "sei", 310),
        Triple("não", "tem", 290),
        Triple("não", "posso", 270),
        Triple("você", "pode", 300),
        Triple("você", "está", 290),
        Triple("para", "fazer", 280),
        Triple("para", "mim", 260),
        Triple("com", "certeza", 310),
        Triple("por", "favor", 390),
        Triple("tempo", "real", 350),
        Triple("teclado", "dinâmico", 220),
        Triple("predição", "de", 240)
    )

    private val enUsWords = listOf(
        "the", "be", "to", "of", "and", "a", "in", "that", "have", "it",
        "for", "not", "on", "with", "he", "as", "you", "do", "at", "this",
        "but", "his", "by", "from", "they", "we", "say", "her", "she", "or",
        "an", "will", "my", "one", "all", "would", "there", "their", "what", "so",
        "up", "out", "if", "about", "who", "get", "which", "go", "me", "when",
        "make", "can", "like", "time", "no", "just", "him", "know", "take", "people",
        "into", "year", "your", "good", "some", "could", "them", "see", "other", "than",
        "then", "now", "look", "only", "come", "its", "over", "think", "also", "back",
        "after", "use", "two", "how", "our", "work", "first", "well", "way", "even",
        "new", "want", "because", "any", "these", "give", "day", "most", "us", "hello",
        "thanks", "thank", "please", "sorry", "yes", "great", "nice", "sure", "okay", "right",
        "keyboard", "typing", "prediction", "dynamic", "hitbox", "touch", "target", "precision", "accurate", "smart",
        "system", "engine", "layout", "design", "interface", "screen", "display", "overlay", "mode", "engineering",
        "advanced", "essential", "settings", "language", "english", "portuguese", "offline", "private", "privacy", "security",
        "password", "android", "mobile", "phone", "device", "latency", "frame", "fast", "smooth", "clean",
        "dark", "light", "theme", "color", "accent", "font", "grid", "margin", "button", "space",
        "delete", "enter", "shift", "symbol", "number", "emoji", "clipboard", "history", "paste", "copy",
        "word", "words", "letter", "letters", "prefix", "context", "model", "dictionary", "corpus", "local",
        "learn", "learning", "reset", "clear", "save", "open", "close", "start", "stop", "check",
        "test", "testing", "benchmark", "verify", "build", "release", "debug", "error", "errors", "fix",
        "code", "project", "file", "folder", "module", "package", "class", "function", "value", "state",
        "always", "never", "still", "today", "tomorrow", "yesterday", "morning", "afternoon", "evening", "night",
        "home", "office", "meeting", "message", "email", "call", "send", "reply", "question", "answer",
        "problem", "solution", "reason", "result", "metric", "report", "status", "ready", "pending", "done",
        "simple", "direct", "honest", "calm", "complete", "stable", "reliable", "accurate", "instant", "responsive",
        "between", "through", "during", "before", "without", "within", "against", "around", "under", "above",
        "better", "best", "more", "less", "very", "much", "many", "every", "each", "both",
        "should", "might", "must", "need", "needs", "working", "running", "building", "making", "trying",
        "really", "actually", "exactly", "probably", "already", "almost", "enough", "quite", "pretty", "totally"
    )

    private val enUsBigrams = listOf(
        Triple("thank", "you", 480),
        Triple("good", "morning", 420),
        Triple("good", "night", 390),
        Triple("how", "are", 380),
        Triple("are", "you", 410),
        Triple("in", "the", 440),
        Triple("on", "the", 390),
        Triple("of", "the", 420),
        Triple("to", "the", 370),
        Triple("for", "the", 350),
        Triple("i", "think", 340),
        Triple("i", "have", 360),
        Triple("i", "need", 310),
        Triple("real", "time", 360),
        Triple("smart", "keyboard", 300),
        Triple("dynamic", "hitbox", 280)
    )
}
