-- Categoria e Subcategoria passam a usar soft-delete (igual ao Produto), pra
-- excluir uma categoria/subcategoria não bater mais em violação de FK quando ainda
-- existem produtos (mesmo inativos) vinculados a elas.
ALTER TABLE categoria ADD COLUMN ativo BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE subcategoria ADD COLUMN ativo BOOLEAN NOT NULL DEFAULT true;
