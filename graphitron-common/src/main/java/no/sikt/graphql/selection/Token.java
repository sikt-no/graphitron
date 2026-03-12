package no.sikt.graphql.selection;

record Token(TokenKind kind, String value) {
    static final Token EOF = new Token(TokenKind.EOF, "<EOF>");

    @Override
    public String toString() {
        return kind == TokenKind.EOF ? "<EOF>" : kind + "(" + value + ")";
    }
}
