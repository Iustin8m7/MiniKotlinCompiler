grammar MiniKotlin;

program
    : (functionDeclaration)* EOF
    ;

functionDeclaration
    : FUN IDENTIFIER LPAREN parameterList? RPAREN COLON type block
    ;

parameterList
    : parameter (COMMA parameter)*
    ;

parameter
    : IDENTIFIER COLON type
    ;

type
    : INT_TYPE
    | STRING_TYPE
    | BOOLEAN_TYPE
    | UNIT_TYPE
    ;

block
    : LBRACE statement* RBRACE
    ;

statement
    : variableDeclaration
    | ifStatement
    | whileStatement
    | variableAssignment
    | returnStatement
    | expression
    ;

variableDeclaration
    : VAR IDENTIFIER COLON type ASSIGN expression
    ;

variableAssignment
    : IDENTIFIER ASSIGN expression
    ;

ifStatement
    : IF LPAREN expression RPAREN block (ELSE block)?
    ;

whileStatement
    : WHILE LPAREN expression RPAREN block
    ;

returnStatement
    : RETURN expression?
    ;

expression
    : IDENTIFIER LPAREN argumentList? RPAREN            # FunctionCallExpr
    | primary                                           # PrimaryExpr
    | NOT expression                                    # NotExpr
    | expression (MULT | DIV | MOD) expression          # MulDivExpr
    | expression (PLUS | MINUS) expression              # AddSubExpr
    | expression (LT | GT | LE | GE) expression         # ComparisonExpr
    | expression (EQ | NEQ) expression                  # EqualityExpr
    | expression AND expression                         # AndExpr
    | expression OR expression                          # OrExpr
    ;

primary
    : LPAREN expression RPAREN                          # ParenExpr
    | INTEGER_LITERAL                                   # IntLiteral
    | STRING_LITERAL                                    # StringLiteral
    | BOOLEAN_LITERAL                                   # BoolLiteral
    | IDENTIFIER                                        # IdentifierExpr
    ;

argumentList
    : expression (COMMA expression)*
    ;

FUN         : 'fun' ;
VAR         : 'var' ;
IF          : 'if' ;
ELSE        : 'else' ;
WHILE       : 'while' ;
RETURN      : 'return' ;

INT_TYPE    : 'Int' ;
STRING_TYPE : 'String' ;
BOOLEAN_TYPE: 'Boolean' ;
UNIT_TYPE   : 'Unit' ;

BOOLEAN_LITERAL : 'true' | 'false' ;
INTEGER_LITERAL : [0-9]+ ;
STRING_LITERAL  : '"' (~["\r\n])* '"' ;

PLUS    : '+' ;
MINUS   : '-' ;
MULT    : '*' ;
DIV     : '/' ;
MOD     : '%' ;
ASSIGN  : '=' ;
EQ      : '==' ;
NEQ     : '!=' ;
LT      : '<' ;
GT      : '>' ;
LE      : '<=' ;
GE      : '>=' ;
AND     : '&&' ;
OR      : '||' ;
NOT     : '!' ;

LPAREN  : '(' ;
RPAREN  : ')' ;
LBRACE  : '{' ;
RBRACE  : '}' ;
COMMA   : ',' ;
COLON   : ':' ;

IDENTIFIER : [a-zA-Z_][a-zA-Z0-9_]* ;

WS           : [ \t\r\n]+ -> skip ;
LINE_COMMENT : '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT: '/*' .*? '*/' -> skip ;
