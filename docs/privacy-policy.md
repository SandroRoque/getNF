# Política de privacidade

> **Rascunho de 2026-10-28, não revisado por advogado** (D104, D105). Antes de publicar: preencher os campos entre
> colchetes, resolver os itens de `docs/play-data-safety.md` › "Antes de publicar" e obter a leitura jurídica. O nome
> do app é provisório.

**Última atualização:** [data da publicação]

Esta política explica quais dados o **[nome do app]** usa, para quê, onde ficam e como você os apaga. O app é
mantido por **Sandro Roque**, pessoa física, responsável pelo tratamento dos dados (controlador, nos termos da Lei
Geral de Proteção de Dados, Lei 13.709/2018). Contato: **[e-mail de contato]**.

## Em resumo

- Sem conta, tudo fica só no seu celular. Nada das suas notas sai dele.
- Com conta, suas notas e seu catálogo são guardados em um servidor no Brasil para sincronizar entre celulares e
  com quem você convidar para a sua caderneta.
- As fotos das notas ficam no celular. Só saem dele se você ativar o envio, e só as de notas que o app não leu bem,
  com os dados do cartão e o CPF cobertos.
- O CPF e o nome do comprador são removidos das páginas da SEFAZ antes de serem guardados.
- Não há publicidade, não vendemos dados e não os usamos para traçar seu perfil.

## O que o app usa, e para quê

### No seu celular (sempre)

- **Notas fiscais:** a chave de acesso lida do QR Code, a página da nota no portal da SEFAZ e o que o app lê dela
  (loja, data, itens, quantidades, preços, descontos, forma de pagamento). Servem para montar seus gastos, o catálogo
  de produtos e a sua inflação pessoal (IPCI).
- **Fotos das notas,** tiradas pela câmera durante a captura, para ler descontos que a página da SEFAZ não detalha.
- **O que você edita:** nomes de produtos, categorias, tamanhos, correções.
- **O tipo de rede em uso** (Wi-Fi ou qual chip de dados móveis), para estimar quantas consultas à SEFAZ ainda cabem
  antes de o portal bloquear a sua rede. Não lemos o nome da rede nem a sua localização.

Esses dados ficam no armazenamento privado do app. Não entram no backup automático do Android: a cópia de segurança
é um arquivo que você exporta em Ajustes e guarda onde quiser.

### No servidor (só se você criar uma conta)

- **Conta:** e-mail, senha (guardada pelo serviço de autenticação, nunca por nós em texto) e um identificador.
- **Nome de exibição** na caderneta, que você pode mudar. Por padrão, a parte do e-mail antes do "@".
- **Notas e catálogo** da caderneta aberta e das outras em que você está: tudo o que está listado acima, menos as
  fotos. As páginas da SEFAZ vão sem o CPF e sem o nome do comprador.
- **Quem escaneou cada nota e quem buscou cada página,** para mostrar "por Ana" aos membros da caderneta e evitar
  consultas repetidas à SEFAZ.
- **Fotos, só com o envio ativado** (Ajustes › Ajudar a melhorar o app): as de notas que o app não conseguiu ler,
  recortadas, em tons de cinza, com o comprovante do cartão, qualquer CPF e o nome do operador cobertos. Servem para
  melhorar a leitura das notas.

### Caderneta compartilhada

Quem está na mesma caderneta vê tudo o que há nela: notas, itens, preços, categorias e quem escaneou cada nota, além
do nome de exibição dos membros. Só convide quem pode ver isso.

## Quem mais processa dados

Usamos estes serviços para operar o app. Eles agem em nosso nome e não recebem os dados para uso próprio:

- **Supabase** (servidor em São Paulo, Brasil): banco de dados, autenticação e arquivos da conta.
- **TypeSafe (Jev):** recebe o texto impresso de um item novo (por exemplo, "CAFE TORRADO MOIDO 500G") e a lista de
  categorias da caderneta, para sugerir a categoria. Não recebe quem você é, a loja, a data nem o preço.
- **Open Food Facts:** recebe o código de barras de um produto, para obter o nome dele. Nenhum dado seu.
- **Google ML Kit:** lê o QR Code e o texto da nota no próprio celular. As imagens não saem do aparelho, mas a
  biblioteca envia ao Google dados de diagnóstico e de uso (modelo do aparelho, versão do app, desempenho, códigos de
  erro e um identificador da instalação), segundo a documentação do Google.
- **Computador do mantenedor:** uma cópia de segurança diária do servidor é guardada por 14 dias, e uma revisão
  noturna das categorias dos produtos roda nesse computador.

Ao abrir a página de uma nota, o celular acessa diretamente o portal da **SEFAZ de Santa Catarina**, que pode exibir
uma verificação da Cloudflare. Esse acesso segue as regras do portal, não desta política.

## O que não fazemos

Não exibimos anúncios, não vendemos nem alugamos dados, não usamos ferramentas de rastreamento ou de análise de
terceiros além do diagnóstico do ML Kit descrito acima, e não usamos seus dados para decisões automatizadas sobre
você.

## Bases legais

- **Execução do serviço que você pede** (art. 7º, V): guardar e sincronizar suas notas e sua caderneta.
- **Legítimo interesse** (art. 7º, IX): segurança, cópias de segurança e diagnóstico de falhas.
- **Consentimento** (art. 7º, I): envio de fotos para melhorar o app, que você ativa e desativa em Ajustes.

## Por quanto tempo

- No celular: até você apagar a nota, os dados do app ou desinstalá-lo.
- No servidor: enquanto você tiver conta. Ao apagá-la, os dados são removidos na hora do banco, e das cópias de
  segurança em até 14 dias.
- Em uma caderneta compartilhada que continua com outros membros, as notas e os itens que vieram de você ficam na
  caderneta, porque fazem parte do registro comum dela; suas fotos enviadas e as marcas de "escaneada por você" são
  apagadas. Antes de sair, você pode copiar suas notas para outra caderneta.

## Seus direitos e como apagar seus dados

Pela LGPD, você pode confirmar se tratamos seus dados, acessá-los, corrigi-los, pedir a portabilidade, revogar o
consentimento e pedir a exclusão.

- **Acesso e portabilidade:** Ajustes › Cópia de segurança › Exportar gera um arquivo com todas as suas notas, fotos
  e o banco de dados do celular.
- **Correção:** tudo é editável no app.
- **Sair de uma caderneta:** Ajustes › Cadernetas.
- **Apagar a conta:** Ajustes › sua conta › Apagar a conta, ou pela página **[endereço da página de exclusão]**, ou
  por e-mail para **[e-mail de contato]**. Se você for o único responsável por uma caderneta com outros membros, será
  preciso passá-la a outro membro antes.
- **Envio de fotos:** desligue em Ajustes a qualquer momento. Ao desligar, as fotos que você já enviou são apagadas
  do servidor na sincronização seguinte (ou quando você entrar na conta de novo, se estiver desconectado).

Respondemos pedidos em até 15 dias. Você também pode reclamar à Autoridade Nacional de Proteção de Dados (ANPD).

## Segurança

As conexões com o servidor usam HTTPS. No servidor, cada caderneta só é visível aos seus membros, por regras do
próprio banco de dados. Nenhum sistema é perfeitamente seguro; se houver um incidente que afete você, avisaremos.

## Crianças

O app não é destinado a menores de 18 anos.

## Mudanças

Avisaremos no app antes de mudanças relevantes nesta política. A data no topo indica a versão.
